package com.neverlate.ui.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fired once, system-wide, when the device finishes booting (US-2). Rebooting wipes every
 * `AlarmManager` alarm the app had scheduled — that is how Android's alarms work, not a bug this
 * feature works around — so every future reminder must be reprogrammed from scratch.
 *
 * `android:exported="true"` in the manifest is required for the system to deliver
 * `BOOT_COMPLETED` here at all, but this receiver does no sensitive work itself: a
 * [BroadcastReceiver]'s execution window is only a few seconds, far too short for a Room read plus
 * potentially many `AlarmManager` calls, so it immediately delegates to [BootRescheduleWorker], a
 * `WorkManager` job that can take as long as it needs. This is the "boot-time" mirror of
 * [ReminderReceiver]'s use of `goAsync`: the same short-execution-window problem, solved with a
 * heavier tool because the work here is not just "a little longer", it is "however long rereading
 * every task and rescheduling its alarm takes".
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        BootRescheduleWorker.enqueue(context)
    }
}
