package org.read.mobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SummaryDiagnosticsState(
    val status: String = "idle",
    val phase: String = "idle",
    val draftCharCount: Int = 0
)

object SummaryDiagnosticsStore {
    private val _uiState = MutableStateFlow(SummaryDiagnosticsState())
    val uiState: StateFlow<SummaryDiagnosticsState> = _uiState.asStateFlow()

    fun markPreparing() {
        _uiState.value = SummaryDiagnosticsState(
            status = "generating",
            phase = "preparing",
            draftCharCount = 0
        )
    }

    fun markGenerating() {
        val current = _uiState.value
        _uiState.value = current.copy(
            status = "generating",
            phase = "generating"
        )
    }

    fun updateDraft(text: String?) {
        val current = _uiState.value
        _uiState.value = current.copy(
            status = "generating",
            phase = if (current.phase == "saving") current.phase else "generating",
            draftCharCount = text?.length?.coerceAtLeast(0) ?: 0
        )
    }

    fun markSaving() {
        val current = _uiState.value
        _uiState.value = current.copy(
            status = "generating",
            phase = "saving"
        )
    }

    fun clear() {
        _uiState.value = SummaryDiagnosticsState()
    }
}
