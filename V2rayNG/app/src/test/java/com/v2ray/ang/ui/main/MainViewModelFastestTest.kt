package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.ServersCache
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The choice a finished round of measurements makes on its own: the fastest link that answered.
 *
 * These rows carry the numbers the app measured, so the test only has to say which of them the screen
 * moves to — and that a row with no measurement is never that row.
 */
class MainViewModelFastestTest {

    private fun server(guid: String, delayMillis: Long): ServersCache = ServersCache(
        guid = guid,
        profile = ProfileItem(configType = EConfigType.CUSTOM, remarks = guid),
        testDelayMillis = delayMillis,
    )

    @Test
    fun theLowestDelayThatAnsweredIsTheFastest() {
        val servers = listOf(server("slow", 240L), server("fast", 96L), server("middle", 150L))
        assertEquals("fast", MainViewModel.fastestAnsweringServer(servers)?.guid)
    }

    @Test
    fun aServerWithoutAnAnswerIsNeverTheFastest() {
        val servers = listOf(server("no-answer", 0L), server("failed", -1L), server("answered", 300L))
        assertEquals("answered", MainViewModel.fastestAnsweringServer(servers)?.guid)
    }

    @Test
    fun aListInWhichNothingAnsweredHasNoFastest() {
        assertNull(MainViewModel.fastestAnsweringServer(listOf(server("no-answer", 0L), server("failed", -1L))))
        assertNull(MainViewModel.fastestAnsweringServer(emptyList()))
    }
}
