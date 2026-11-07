package com.v2ray.ang.dto.entities

data class ServerAffiliationInfo(
    var testDelayMillis: Long = 0L,
    var testSampleCount: Int = 0,
    var testSuccessfulSamples: Int = 0,
    var testJitterMillis: Long = 0L,
    var testKind: String = "",
)
