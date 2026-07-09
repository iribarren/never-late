package com.neverlate.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neverlate.data.tasks.TaskRepository
import com.neverlate.domain.tasks.WeeklyTaskStats
import com.neverlate.domain.tasks.weeklyStatsFor
import java.time.Clock
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Everything the Stats screen needs to render itself — the same Loading/Content/Empty shape
 * [com.neverlate.ui.tasks.TasksUiState] already uses, so a screen reader of either sealed
 * interface recognizes the pattern immediately.
 */
sealed interface StatsUiState {
    data object Loading : StatsUiState
    data class Content(val stats: WeeklyTaskStats) : StatsUiState
    data object Empty : StatsUiState
}

/**
 * Derives [WeeklyTaskStats] from [TaskRepository.observeTasks] through the pure
 * [weeklyStatsFor] (US-5, the feature's pedagogical point): this ViewModel is deliberately the
 * *only* impure thing in the chain — it is the one place that reads [clock] and observes the
 * repository — so [weeklyStatsFor] itself stays a trivial, deterministic JVM unit test target,
 * and *this* class is instead tested with a fixed [clock] plus `runTest`/`StandardTestDispatcher`
 * (`kotlinx-coroutines-test`, see `StatsViewModelTest`) to control virtual time over [uiState].
 *
 * [clock] mirrors [com.neverlate.domain.tasks.ReminderPlanning.kt]'s injected-time seam: it
 * defaults to the real system clock so callers never have to pass one, but a test can construct
 * this ViewModel with a fixed [Clock] instead.
 */
class StatsViewModel(
    private val repository: TaskRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {

    val uiState: StateFlow<StatsUiState> = repository.observeTasks()
        .map { tasks ->
            if (tasks.isEmpty()) {
                StatsUiState.Empty
            } else {
                StatsUiState.Content(weeklyStatsFor(tasks, clock.millis(), clock.zone))
            }
        }
        // Same sharing policy as TasksViewModel.uiState: shared while at least one collector is
        // present, kept alive 5s past the last one leaving to survive a configuration change.
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState.Loading)
}
