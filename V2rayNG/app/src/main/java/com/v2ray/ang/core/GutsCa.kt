package com.v2ray.ang.core

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import android.security.KeyChain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.util.concurrent.TimeUnit

/**
 * The persistent local CA of GUTS certificate/fetch mode.
 *
 * The certificate and its private key live in the app's private files directory, never in the
 * disposable per-session cache. The native client creates the pair, so Android and the Go TLS
 * server cannot disagree about the format; this object only asks it to do so, shows its public
 * fingerprint, saves the public certificate where Settings can find it, and opens the platform's
 * installer. The private key is never copied out of private storage.
 */
object GutsCa {

    internal data class Files(val cert: File, val key: File)

    internal data class Material(val files: Files, val fingerprint: String)

    private const val DIRECTORY = "guts-ca"
    private const val CERT_FILE = "ca.crt"
    private const val KEY_FILE = "ca.key"
    const val DOWNLOAD_NAME = "omni-guts-ca.crt"
    const val INSTALL_NAME = "Omni GUTS Local CA"

    internal fun files(context: Context): Files {
        val directory = File(context.applicationContext.filesDir, DIRECTORY)
        return Files(File(directory, CERT_FILE), File(directory, KEY_FILE))
    }

    fun exists(context: Context): Boolean = files(context).let { it.cert.isFile && it.key.isFile }

    fun fingerprint(context: Context): String? {
        val certificate = files(context).cert
        if (!certificate.isFile) return null
        return runCatching { fingerprintOf(certificate.readBytes()) }.getOrNull()
    }

    internal fun fingerprintOf(pemOrDer: ByteArray): String {
        val encoded = parseCertificate(pemOrDer)
        return MessageDigest.getInstance("SHA-256").digest(encoded)
            .joinToString(":") { "%02X".format(it.toInt() and 0xff) }
    }

    /** Creates the CA on first use and loads the same one on every later call. */
    internal suspend fun ensure(context: Context): Material = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val target = files(appContext)
        target.cert.parentFile?.mkdirs()
        val binary = GutsCoreManager.binary(appContext)
        check(binary.canExecute()) { "The GUTS client is not available on this device" }

        val process = ProcessBuilder(
            binary.absolutePath,
            "-make-ca",
            "-ca-cert=${target.cert.absolutePath}",
            "-ca-key=${target.key.absolutePath}",
        )
            .redirectErrorStream(true)
            .start()
        runCatching { process.outputStream.close() }
        val output = runCatching { process.inputStream.bufferedReader().readText() }.getOrDefault("")
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Creating the local certificate timed out")
        }
        check(process.exitValue() == 0 && target.cert.isFile && target.key.isFile) {
            output.trim().ifBlank { "Creating the local certificate failed" }
        }
        Material(target, fingerprintOf(target.cert.readBytes()))
    }

    /** Saves only the public certificate in Downloads, where Android's CA picker can open it. */
    fun saveToDownloads(context: Context, certFile: File): String {
        val resolver = context.applicationContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, DOWNLOAD_NAME)
            put(MediaStore.Downloads.MIME_TYPE, "application/x-x509-ca-cert")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Android refused to create the certificate download")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                certFile.inputStream().use { input -> input.copyTo(output) }
            } ?: error("Android refused to write the certificate download")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return DOWNLOAD_NAME
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    /** The platform installer intent for the public DER certificate. */
    fun installIntent(certFile: File): Intent =
        KeyChain.createInstallIntent()
            .putExtra(KeyChain.EXTRA_CERTIFICATE, parseCertificate(certFile.readBytes()))
            .putExtra(KeyChain.EXTRA_NAME, INSTALL_NAME)

    private fun parseCertificate(pemOrDer: ByteArray): ByteArray =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(pemOrDer))
            .encoded
}
