package com.v2ray.ang.ui.main

import android.content.Context
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ServersCache
import java.util.IllegalFormatException
import kotlin.math.abs

/**
 * What one measurement turned out to be, and what a person is told about it in one line.
 *
 * The number in front is always the **fastest real response** — the lowest figure that actually came
 * back — because that is what a person means when they ask for "the ping". The typical and the slowest
 * responses sit next to it, so the number can be checked against the spread instead of being taken on
 * faith, and a profile that only answered some of its samples says how many answered rather than
 * hiding it.
 *
 * The row and the status bar both render from this, so the same measurement can never be described two
 * different ways in the two places a person looks at it: the row used to show the fastest and the status
 * bar the median, which read as two links with different pings.
 *
 * [delayMillis] follows the app's convention: a positive figure is a response time, `0` means the
 * profile was not measured in this round, and a negative figure means it was measured and nothing
 * answered.
 */
internal data class PingReadout(
    val delayMillis: Long,
    val medianMillis: Long = 0L,
    val maxMillis: Long = 0L,
    val sampleCount: Int = 0,
    val successfulSamples: Int = 0,
    val kind: String = "",
    val partial: Boolean = false,
) {
    /** No measurement of this profile happened in this round; there is nothing to say about it. */
    val untested: Boolean get() = delayMillis == 0L && sampleCount == 0

    /** Measured, and nothing came back: the profile is not reported as "slow", it is reported as silent. */
    val unanswered: Boolean get() = delayMillis < 0L

    /** The figure a person calls the ping, when there is one. */
    val fastest: Long? get() = delayMillis.takeIf { it > 0L }

    /** How far the slowest sample is above the fastest, when two samples gave a spread at all. */
    val spreadMillis: Long
        get() = if (fastest != null && maxMillis >= delayMillis) maxMillis - delayMillis else 0L

    companion object {
        /**
         * The readout of [server], which carries what the round measured into the row.
         *
         * Only the fields a real test writes are read: a profile whose row still holds a value from an
         * earlier round is indistinguishable here, which is why a new round clears the old values first
         * (see `MainViewModel.clearTestDelays`).
         */
        fun of(server: ServersCache): PingReadout = PingReadout(
            delayMillis = server.testDelayMillis,
            medianMillis = server.testMedianMillis,
            maxMillis = server.testMaxMillis,
            sampleCount = server.testSampleCount,
            successfulSamples = server.testSuccessfulSamples,
            kind = server.testKind,
            partial = server.testPartial,
        )

        /** The text for [readout], empty when there is nothing worth saying. */
        fun describe(context: Context, readout: PingReadout): String = try {
            when {
                readout.untested -> ""
                readout.unanswered && readout.sampleCount > 0 -> context.getString(
                    R.string.server_test_no_response_samples,
                    readout.successfulSamples,
                    readout.sampleCount,
                )
                readout.unanswered -> context.getString(R.string.server_test_no_response)
                readout.sampleCount > 0 && readout.kind.isNotBlank() && readout.maxMillis > 0L -> context.getString(
                    if (readout.partial) {
                        R.string.server_test_delay_detailed_partial
                    } else {
                        R.string.server_test_delay_detailed_value
                    },
                    readout.kind,
                    readout.delayMillis,
                    readout.successfulSamples,
                    readout.sampleCount,
                    readout.medianMillis,
                    readout.maxMillis,
                )
                readout.sampleCount > 0 && readout.kind.isNotBlank() -> context.getString(
                    R.string.server_test_delay_detailed_partial_no_spread,
                    readout.kind,
                    readout.delayMillis,
                    readout.successfulSamples,
                    readout.sampleCount,
                )
                else -> context.getString(R.string.server_test_delay_value, readout.delayMillis)
            }
        } catch (_: IllegalFormatException) {
            // A bad positional placeholder in a translated resource must not crash row rendering.
            readout.fastest?.let {
                "${readout.kind.takeIf(String::isNotBlank)?.plus(" ").orEmpty()}$it ms"
            } ?: readout.delayMillis.toString()
        }

        /**
         * The same measurement for the status bar: the fastest response, the typical one, and how many
         * samples answered. It deliberately does not repeat the word for "connection succeeded" that the
         * row's number carries, because the bar already says what it is describing.
         */
        fun describeForStatusBar(context: Context, readout: PingReadout): String = try {
            when {
                readout.untested -> context.getString(R.string.connection_test_untested)
                readout.unanswered && readout.sampleCount > 0 -> context.getString(
                    R.string.server_test_no_response_samples,
                    readout.successfulSamples,
                    readout.sampleCount,
                )
                readout.unanswered -> context.getString(
                    R.string.connection_test_error,
                    context.getString(R.string.connection_test_empty_message),
                )
                readout.sampleCount > 0 && readout.kind.isNotBlank() -> context.getString(
                    R.string.connection_test_ping_summary,
                    readout.delayMillis,
                    readout.medianMillis.coerceAtLeast(readout.delayMillis),
                    readout.maxMillis.coerceAtLeast(readout.delayMillis),
                )
                else -> context.getString(R.string.connection_test_ping_value, readout.delayMillis)
            }
        } catch (_: IllegalFormatException) {
            readout.fastest?.let { "$it ms" } ?: readout.delayMillis.toString()
        }

    }
}

/** How far two readouts disagree about the same link, for the row that remembers its last figures. */
internal fun PingReadout.sameMeasurementAs(other: PingReadout): Boolean =
    abs(delayMillis - other.delayMillis) == 0L && medianMillis == other.medianMillis && maxMillis == other.maxMillis
