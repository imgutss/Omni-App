package com.v2ray.ang.omni.lab

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

private const val LOOPBACK = "127.0.0.1"

/** Keeps the built outbound and its dependencies, changing only the tag required by native openExit. */
fun createOmniNativeExitConfiguration(originalConfig: String): String {
    val root = JsonParser.parseString(originalConfig).asJsonObject
    val outbounds = root.getAsJsonArray("outbounds") ?: throw IllegalArgumentException("Configuration has no outbounds")
    require(outbounds.size() > 0) { "Configuration has no outbounds" }
    val first = outbounds[0].asJsonObject
    require(first.has("protocol")) { "The first outbound has no protocol" }
    first.addProperty("tag", "exit-node")
    root.add("outbounds", outbounds)
    return root.toString()
}

/** Optional loopback adapter for a core configuration that explicitly needs a local SOCKS exit. */
fun createOmniExitConfiguration(originalConfig: String, localSocksPort: Int): String {
    require(localSocksPort in 1..65535) { "Invalid loopback SOCKS port" }
    val root = JsonParser.parseString(originalConfig).asJsonObject
    val outbounds = root.getAsJsonArray("outbounds") ?: throw IllegalArgumentException("Configuration has no outbounds")
    require(outbounds.size() > 0) { "Configuration has no outbounds" }
    val originalOutbound = outbounds[0].asJsonObject
    val replacement = JsonObject()
    listOf("protocol", "settings", "streamSettings", "mux").forEach { tag -> originalOutbound.get(tag)?.let { replacement.add(tag, it) } }
    if (!replacement.has("protocol")) throw IllegalArgumentException("The first outbound has no protocol")
    replacement.addProperty("tag", "proxy")
    val replacementStream = replacement.getAsJsonObject("streamSettings") ?: JsonObject().also { replacement.add("streamSettings", it) }
    replacementStream.add("sockopt", JsonObject().apply { addProperty("tag", "omni-exit-loopback") })
    val loopback = JsonObject().apply {
        addProperty("protocol", "socks")
        addProperty("tag", "omni-exit-loopback")
        add("settings", JsonObject().apply {
            add("servers", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("address", LOOPBACK)
                    addProperty("port", localSocksPort)
                })
            })
        })
    }
    val newOutbounds = JsonArray().apply {
        add(replacement)
        for (index in 1 until outbounds.size()) add(outbounds[index])
        add(loopback)
    }
    root.add("outbounds", newOutbounds)
    return root.toString()
}

/** This interface cannot see the native object; the Android service adapter owns openExit/closeExit. */
fun interface OmniCoreStatsQuery {
    fun queryStats(name: String, direct: String): Long
}

interface OmniCoreRuntime {
    val socksPort: Int
    fun start(exitConfiguration: String): Boolean
    fun stop()
}
