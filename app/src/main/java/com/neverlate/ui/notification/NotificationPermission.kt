package com.neverlate.ui.notification

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Requests the runtime `POST_NOTIFICATIONS` permission, once, the first time this effect enters
 * the composition — meant to be dropped into the tasks screen so the ask happens *in context*
 * (the user is looking at their tasks, so "show these on the lock screen" makes sense) rather than
 * intrusively on the very first app launch with no explanation.
 *
 * Three things make this new concept behave well:
 * - **API guard:** `POST_NOTIFICATIONS` only exists on Android 13+ (`TIRAMISU`). On API 24–32 the
 *   permission is granted implicitly, so this effect does nothing and the notification just works.
 * - **`rememberLauncherForActivityResult` + `RequestPermission`:** the Compose-friendly way to
 *   launch the system permission dialog and get its yes/no back, without manually overriding
 *   `Activity.onRequestPermissionsResult`.
 * - **Graceful degradation:** if the user denies, nothing here throws or blocks; the notification
 *   simply will not be shown (see [TasksNotificationService], which checks
 *   `areNotificationsEnabled()` before promoting to the foreground). If the user grants, we poke
 *   the service so the notification appears immediately instead of only at the next refresh.
 *
 * The dialog is only launched when the permission is not already granted; the system itself stops
 * showing it once the user has denied twice, so this never becomes a nag.
 */
@Composable
fun RequestNotificationPermissionEffect() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            // Now that we may post, re-evaluate immediately so the notification shows without
            // waiting for the next write or the periodic worker.
            TasksNotificationService.refresh(context)
        }
    }

    LaunchedEffect(Unit) {
        val alreadyGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

        if (!alreadyGranted) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
