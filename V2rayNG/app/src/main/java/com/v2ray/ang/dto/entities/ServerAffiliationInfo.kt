package com.v2ray.ang.dto.entities

data class ServerAffiliationInfo(
    /** The fastest real response of the last test. */
    var testDelayMillis: Long = 0L,
    var testSampleCount: Int = 0,
    var testSuccessfulSamples: Int = 0,
    /** Standard deviation of the successful samples. */
    var testJitterMillis: Long = 0L,
    var testKind: String = "",
    var testMedianMillis: Long = 0L,
    var testMaxMillis: Long = 0L,
    /** True when some sample of that test got no answer. */
    var testPartial: Boolean = false,
)
