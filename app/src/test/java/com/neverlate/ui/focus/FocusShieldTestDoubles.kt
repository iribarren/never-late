package com.neverlate.ui.focus

/**
 * In-memory [FocusShieldController] fake (`docs/specs/2026-08-18-focus-mode-shielding.md`) — no
 * `NotificationManager`, no `WorkManager`, so every JVM test in this package can construct
 * [FocusViewModel] without Robolectric. [callLog] records every method invocation, in order,
 * across **all** methods (not one list per method) so a test can assert cross-method ordering —
 * e.g. that `applyDoNotDisturb()` runs after the receipt is written (AC-10) — the same
 * "assert call order, not just call presence" requirement the spec's R6 calls out.
 */
class FakeFocusShieldController(
    private var policyAccessGranted: Boolean = true,
    private var currentFilter: Int = ALL_FILTER,
    private var applyDoNotDisturbResult: Boolean = true,
) : FocusShieldController {

    /** Every call this fake received, in order, e.g. `["applyDoNotDisturb", "restore(false)"]`. */
    val callLog = mutableListOf<String>()

    /** Every `sessionActive` argument passed to [restore], in call order. */
    val restoreCalls = mutableListOf<Boolean>()

    var cancelBackstopCallCount = 0
        private set

    fun setPolicyAccessGranted(granted: Boolean) {
        policyAccessGranted = granted
    }

    fun setCurrentFilter(filter: Int) {
        currentFilter = filter
    }

    fun setApplyDoNotDisturbResult(result: Boolean) {
        applyDoNotDisturbResult = result
    }

    override suspend fun applyDoNotDisturb(): Boolean {
        callLog += "applyDoNotDisturb"
        if (applyDoNotDisturbResult) currentFilter = PRIORITY_FILTER
        return applyDoNotDisturbResult
    }

    override suspend fun restore(sessionActive: Boolean) {
        callLog += "restore($sessionActive)"
        restoreCalls += sessionActive
    }

    override fun isPolicyAccessGranted(): Boolean = policyAccessGranted

    override fun currentInterruptionFilter(): Int = currentFilter

    override fun cancelBackstop() {
        callLog += "cancelBackstop"
        cancelBackstopCallCount++
    }

    companion object {
        /** Mirrors `NotificationManager.INTERRUPTION_FILTER_ALL` (value `1`) — an arbitrary "not
         *  currently applying the shield" default, distinct from [PRIORITY_FILTER]. */
        const val ALL_FILTER = 1

        /** Mirrors `NotificationManager.INTERRUPTION_FILTER_PRIORITY` (value `2`) — the filter
         *  the real controller applies (D2); tests assert against this same value. */
        const val PRIORITY_FILTER = 2
    }
}
