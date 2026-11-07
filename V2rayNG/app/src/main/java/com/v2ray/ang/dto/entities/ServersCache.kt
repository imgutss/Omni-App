package com.v2ray.ang.dto.entities

data class ServersCache(
    val guid: String,
    val profile: ProfileItem,
    val testDelayMillis: Long = 0L,
    val testSampleCount: Int = 0,
    val testSuccessfulSamples: Int = 0,
    val testJitterMillis: Long = 0L,
    val testKind: String = "",
)
