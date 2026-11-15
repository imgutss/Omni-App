package com.v2ray.ang.omni

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OmniSyncCenterLogicTest {
    private val panels = listOf(
        OmniPanel(id = "panel-a", name = "A", baseUrl = "https://a.example", subscriptionUrl = "https://a.example/s/a", subscriptionId = "sub-a"),
        OmniPanel(id = "panel-b", name = "B", baseUrl = "https://b.example", subscriptionUrl = "https://b.example/s/b", subscriptionId = "sub-b"),
    )

    @Test
    fun selectionIsReconciledByStablePanelId() {
        assertEquals(setOf("panel-a"), reconcileOmniPanelSelection(panels, setOf("panel-a", "deleted-panel")))
        assertEquals(emptySet<String>(), reconcileOmniPanelSelection(emptyList(), setOf("panel-a")))
    }

    @Test
    fun resultsAreKeyedByPanelAndFailuresAreNotHidden() {
        val first = OmniSyncPanelResult("panel-a", OmniSyncOperation.SYNC, true, configCount = 12)
        val second = OmniSyncPanelResult("panel-b", OmniSyncOperation.SYNC, false, detail = "HTTP 403")
        val merged = mergeOmniSyncResult(mergeOmniSyncResult(emptyMap(), first), second)
        assertEquals(2, merged.size)
        assertEquals(first, merged["panel-a"])
        assertEquals(second, merged["panel-b"])
        assertEquals(OmniSyncMessage.PARTIAL_FAILURE, omniSyncRunSummary(merged.values))
        assertEquals(OmniSyncMessage.ALL_SUCCEEDED, omniSyncRunSummary(listOf(first)))
        assertEquals(OmniSyncMessage.NO_PANEL_SELECTED, omniSyncRunSummary(emptyList()))
    }
}
