package com.v2ray.ang.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.main.MainActivity
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLDecoder

class UrlSchemeActivity : BaseComponentActivity() {

    private val omniSchemes = setOf(
        "vless", "vmess", "trojan", "ss", "hysteria2", "hy2", "tuic", "wireguard", "aether",
        "masterdns", "cottendns", "soar", "omni", "guts",
    )

    // These carry an opaque Base64 payload whose '+' and '/' a URL decoder would change.
    private val opaqueProfileSchemes = setOf("masterdns", "cottendns", "soar", "guts")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            when (intent.action) {
                Intent.ACTION_SEND -> {
                    if (intent.type == "text/plain") {
                        parseUri(intent.getStringExtra(Intent.EXTRA_TEXT), null)
                    } else {
                        failAndClose()
                    }
                }

                Intent.ACTION_VIEW -> {
                    val uri: Uri? = intent.data
                    when {
                        uri?.scheme == "v2rayng" && uri.host == "install-config" -> {
                            parseUri(uri.getQueryParameter("url").orEmpty(), uri.fragment)
                        }

                        uri?.scheme == "v2rayng" && uri.host == "install-sub" -> {
                            parseUri(uri.getQueryParameter("url").orEmpty(), uri.fragment)
                        }

                        uri?.scheme == "omni" && (uri.host == "install-config" || uri.host == "install-sub") -> {
                            parseUri(uri.getQueryParameter("url").orEmpty(), uri.fragment)
                        }

                        uri != null && uri.scheme?.lowercase() in omniSchemes -> {
                            // Open config links through the same batch importer as QR/clipboard.
                            parseUri(uri.toString(), null)
                        }

                        else -> failAndClose()
                    }
                }

                else -> failAndClose()
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Error processing URL scheme", e)
            failAndClose()
        }
    }

    @Composable
    override fun ScreenContent() {
    }

    private fun parseUri(uriString: String?, fragment: String?) {
        if (uriString.isNullOrEmpty()) {
            failAndClose()
            return
        }
        val inputUri = Uri.parse(uriString)
        val opaqueProfile = inputUri.scheme?.lowercase() in opaqueProfileSchemes
        // A form URL decoder turns '+' in a standard Base64 profile into a space.
        // These schemes carry an opaque payload, so preserve every character exactly.
        var decodedUrl = if (opaqueProfile) uriString else URLDecoder.decode(uriString, "UTF-8")
        val uri = Uri.parse(decodedUrl)
        if (uri.fragment.isNullOrEmpty() && !fragment.isNullOrEmpty()) {
            decodedUrl += "#${fragment}"
        }
        // Profile links can contain server keys. Never write the payload to logcat.
        LogUtil.i(AppConfig.TAG, "Importing ${inputUri.scheme ?: "shared"} configuration")
        lifecycleScope.launch {
            val (count, countSub) = withContext(Dispatchers.IO) {
                runCatching { AngConfigManager.importBatchConfig(decodedUrl, "", false) }
                    .getOrElse {
                        LogUtil.e(AppConfig.TAG, "Configuration import failed", it)
                        0 to 0
                    }
            }
            // An open main screen reloads its tabs.
            if (count + countSub > 0) MessageHelper.sendMsg2UI(applicationContext, AppConfig.MSG_SERVERS_CHANGED, "")
            toast(if (count + countSub > 0) R.string.import_subscription_success else R.string.import_subscription_failure)
            finishToMain()
        }
    }

    private fun failAndClose() {
        toastError(R.string.toast_failure)
        finishToMain()
    }

    private fun finishToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
