package com.v2ray.ang.ui.main

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OmniDrawerTest {
    @Test fun fiveHubToolsAreNotDuplicatedInDrawer() {
        val hubOnly = setOf(MainDestination.OmniPrivacy, MainDestination.OmniDnsLab,
            MainDestination.OmniRoutingStudio, MainDestination.OmniSync, MainDestination.OmniSpeed)
        assertTrue(drawerItems.none { it in hubOnly })
        assertEquals(MainDestination.entries.toSet() - hubOnly, drawerItems.toSet())
        // 13 was the count before the Firefox entry: it is a tool of this fork too, and the drawer shows it
        // first among the fork's entries so a person who came for the free allowance finds it.
        assertEquals(14, drawerItems.size)
        assertEquals(drawerItems.size, drawerItems.distinct().size)
        assertTrue(drawerItems.contains(MainDestination.OmniPanel))
        assertTrue(drawerItems.contains(MainDestination.Firefox))
        assertEquals(MainDestination.Firefox, drawerItems.first { it in setOf(MainDestination.Firefox, MainDestination.OmniPanel) })
    }
    @Test fun logsAndBackupHaveSemanticArtworkInsteadOfPositionBasedIcons() {
        assertEquals("logs", MainDestination.Logcat.referenceArtwork())
        assertEquals("backup", MainDestination.OmniPrivacy.referenceArtwork())
        assertEquals("backup", MainDestination.BackupRestore.referenceArtwork())
        assertEquals("shield", MainDestination.Firefox.referenceArtwork())
        assertNotEquals("theme", MainDestination.Logcat.referenceArtwork())
        assertTrue(MainDestination.entries.all { it.referenceArtwork().isNotBlank() })
    }
}
