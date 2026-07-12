package com.neverlate.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neverlate.R
import com.neverlate.domain.tasks.WeeklyTaskStats
import com.neverlate.ui.components.BrandIconChip
import com.neverlate.ui.components.MessageState
import com.neverlate.ui.components.brandedTopAppBarColors
import com.neverlate.ui.theme.NeverLateTheme
import java.text.NumberFormat

/**
 * Stateful wrapper: obtains [StatsViewModel] via `hiltViewModel()` (feature 13d) and forwards its
 * state to the stateless [StatsScreen] — the same route/screen split every other screen in this
 * app follows (e.g. [com.neverlate.ui.tasks.TasksRoute]).
 */
@Composable
fun StatsRoute(
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    viewModel: StatsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    StatsScreen(uiState = uiState, onBack = onBack, modifier = modifier)
}

/**
 * Stateless composable (US-2/US-3): a branded top bar plus a vertical stack of three single-number
 * stat cards. Reached only as a secondary destination (`AppNavHost`'s `Routes.STATS`, opened from
 * the Tasks top bar) — [onBack] is always supplied in practice, but stays nullable for the same
 * state-hoisting/testability reason every other screen in this app keeps it nullable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    uiState: StatsUiState,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stats_title)) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.stats_back_content_description),
                            )
                        }
                    }
                },
                colors = brandedTopAppBarColors(),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            when (uiState) {
                // Nothing to show yet: avoids a one-frame flash of the empty state while loading,
                // same reasoning as TasksScreen's Loading branch.
                is StatsUiState.Loading -> Unit
                is StatsUiState.Empty -> MessageState(
                    icon = Icons.AutoMirrored.Filled.Assignment,
                    message = stringResource(R.string.stats_empty),
                    modifier = Modifier.fillMaxSize(),
                )
                is StatsUiState.Content -> StatsContent(
                    stats = uiState.stats,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** The three stat cards, in the fixed order the spec's *Layout* section lists them. */
@Composable
private fun StatsContent(stats: WeeklyTaskStats, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StatCard(
            icon = Icons.Filled.TaskAlt,
            value = stats.completedThisWeek.toString(),
            label = stringResource(R.string.stats_completed_label),
        )
        StatCard(
            icon = Icons.Filled.CheckCircle,
            // "—" rather than "0%": a null ratio means "nothing dated to measure", not "always late".
            value = stats.onTimePercent?.let { percent -> formatPercent(percent) }
                ?: stringResource(R.string.stats_on_time_undefined),
            label = stringResource(R.string.stats_on_time_label),
        )
        StatCard(
            icon = Icons.Filled.Schedule,
            value = stats.dueSoon.toString(),
            label = stringResource(R.string.stats_due_soon_label),
        )
    }
}

/**
 * One stat: a leading [BrandIconChip] (decorative — the number+label pair already conveys the
 * meaning, same rule [com.neverlate.ui.tasks.TaskRow] follows for its own chip), a large number in
 * the Material 3 type scale, and a smaller caption label underneath — the "big number + label"
 * card shape the spec's *Visual & UX Design* section calls for. Built entirely from
 * [MaterialTheme]/[BrandIconChip]/[Card] — no bespoke sizes or colors, so it stays correct in
 * light, dark, and Material You for free, and reflows instead of clipping at the largest font
 * scale (the [Column] has no fixed height).
 */
@Composable
private fun StatCard(icon: ImageVector, value: String, label: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrandIconChip(icon = icon)
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text(text = value, style = MaterialTheme.typography.displaySmall)
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Locale-aware percentage text, the same [NumberFormat] pattern [com.neverlate.ui.tasks.TaskRow]
 *  already uses for the deadline progress bar's percent label (feature 19). */
@Composable
private fun formatPercent(percent: Int): String {
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale) { NumberFormat.getPercentInstance(locale) }
    return format.format(percent / 100.0)
}

@Preview(showBackground = true)
@Composable
private fun StatsScreenContentPreview() {
    NeverLateTheme {
        StatsScreen(
            uiState = StatsUiState.Content(
                WeeklyTaskStats(completedThisWeek = 5, onTimePercent = 80, dueSoon = 2),
            ),
            onBack = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun StatsScreenUndefinedOnTimePreview() {
    NeverLateTheme {
        StatsScreen(
            uiState = StatsUiState.Content(
                WeeklyTaskStats(completedThisWeek = 1, onTimePercent = null, dueSoon = 0),
            ),
            onBack = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun StatsScreenEmptyPreview() {
    NeverLateTheme {
        StatsScreen(uiState = StatsUiState.Empty, onBack = {})
    }
}
