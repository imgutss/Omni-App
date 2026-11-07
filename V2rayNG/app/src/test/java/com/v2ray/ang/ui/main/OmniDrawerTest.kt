package com.v2ray.ang.ui.main

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OmniDrawerTest {
    @Test fun fiveHubToolsAreNotDuplicatedInDrawer() {
        val hubOnly = setOf(MainDestination.OmniPrivacy, MainDestination.OmniDnsLab,
            MainDestination.OmniRoutingStudio, MainDestination.OmniSync, MainDestination.OmniSpeed)
        assertTrue(drawerItems.none { it in hubOnly })
        assertEquals(MainDestination.entries.toSet() - hubOnly, drawerItems.toSet())
        assertEquals(13, drawerItems.size)
        assertEquals(drawerItems.size, drawerItems.distinct().size)
        assertTrue(drawerItems.contains(MainDestination.OmniPanel))
    }
    @Test fun logsAndBackupHaveSemanticArtworkInsteadOfPositionBasedIcons() {
        assertEquals("logs", MainDestination.Logcat.referenceArtwork())
        assertEquals("backup", MainDestination.OmniPrivacy.referenceArtwork())
        assertEquals("backup", MainDestination.BackupRestore.referenceArtwork())
        assertNotEquals("theme", MainDestination.Logcat.referenceArtwork())
        assertTrue(MainDestination.entries.all { it.referenceArtwork().isNotBlank() })
    }
}
