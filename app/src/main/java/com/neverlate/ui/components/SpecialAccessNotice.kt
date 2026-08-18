package com.neverlate.ui.components

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Shared "special access" notice (`docs/specs/2026-08-18-focus-mode-shielding.md`, D9): the
 * *check → explain → send to the system Settings screen* idiom every **special access** in this
 * app follows — `ACCESS_NOTIFICATION_POLICY` (Modo Foco's Do Not Disturb measure) and
 * `SCHEDULE_EXACT_ALARM` (feature 09's reminders) alike. A special access is never granted by a
 * runtime permission dialog, so the only thing an app can do is check, explain the trade-off, and
 * offer one tap to the screen that grants it — then leave the person alone (D10: a missing grant
 * is an ordinary, expected state, never an error).
 *
 * Extracted from `SettingsScreen.kt`'s private `ExactAlarmPermissionNotice` (feature 09) rather
 * than writing a second, near-identical composable for the Focus entry dialog's Do-Not-Disturb
 * row — the same "one mapping, thin per-call-site resolvers" shape `ColorRole.kt` and the widget
 * refactor already established. The extraction also **fixes a real defect it inherits**: the
 * original only checked [isGranted] once per composition, so returning from system Settings after
 * granting left the notice stale until the screen was left and reopened. This version observes
 * [Lifecycle.Event.ON_RESUME] and re-reads [isGranted] then, so the notice disappears the moment
 * the person comes back having granted it — no dialog/screen remount needed.
 *
 * Renders nothing at all when [isGranted] is currently `true`.
 *
 * @param isGranted re-evaluated on first composition and on every `ON_RESUME` — a lambda, not a
 *   plain `Boolean`, because "is this granted" can only be answered by asking the platform fresh
 *   each time (there is no change callback for a special access).
 * @param message the honest, per-measure explanation shown above the action button.
 * @param actionLabel the action button's label (e.g. "Conceder acceso").
 * @param settingsIntent builds the `Intent` that opens the system screen granting this access —
 *   a lambda (not a plain `Intent`) so it can be built lazily, only when actually tapped.
 */
@Composable
fun SpecialAccessNotice(
    isGranted: () -> Boolean,
    message: String,
    actionLabel: String,
    settingsIntent: () -> Intent,
    modifier: Modifier = Modifier,
) {
    var granted by remember { mutableStateOf(isGranted()) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = isGranted()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (granted) return

    val context = LocalContext.current
    Column(modifier = modifier) {
        Text(text = message, style = MaterialTheme.typography.bodySmall)
        TextButton(
            onClick = { context.startActivity(settingsIntent()) },
            modifier = Modifier.minimumInteractiveComponentSize(),
        ) {
            Text(actionLabel)
        }
    }
}
