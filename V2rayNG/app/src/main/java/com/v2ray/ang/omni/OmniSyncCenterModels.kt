package com.v2ray.ang.omni

/** The operation and panel ID are explicit so a result can never be attached to the wrong row. */
enum class OmniSyncOperation { SYNC, CHECK }

data class OmniSyncPanelResult(
    val panelId: String,
    val operation: OmniSyncOperation,
    val success: Boolean,
    val configCount: Int? = null,
    val detail: String = "",
)

data class OmniSyncCenterUiState(
    val panels: List<OmniPanel> = emptyList(),
    val selectedPanelIds: Set<String> = emptySet(),
    val results: Map<String, OmniSyncPanelResult> = emptyMap(),
    val loading: Boolean = false,
    val running: Boolean = false,
    val activePanelId: String? = null,
    val message: OmniSyncMessage? = null,
)

enum class OmniSyncMessage { LOAD_FAILED, NO_PANEL_SELECTED, PARTIAL_FAILURE, ALL_SUCCEEDED }

fun reconcileOmniPanelSelection(panels: List<OmniPanel>, selected: Set<String>): Set<String> {
    val validIds = panels.asSequence().map { it.id }.toSet()
    return selected.filterTo(linkedSetOf()) { it in validIds }
}

fun mergeOmniSyncResult(
    results: Map<String, OmniSyncPanelResult>,
    result: OmniSyncPanelResult,
): Map<String, OmniSyncPanelResult> = results + (result.panelId to result)

fun omniSyncRunSummary(results: Collection<OmniSyncPanelResult>): OmniSyncMessage = when {
    results.isEmpty() -> OmniSyncMessage.NO_PANEL_SELECTED
    results.all { it.success } -> OmniSyncMessage.ALL_SUCCEEDED
    else -> OmniSyncMessage.PARTIAL_FAILURE
}
