package com.neverlate.backend.common

/**
 * Models the classic PATCH ambiguity: a nullable field can be **omitted** ("don't touch this")
 * or **explicitly set to `null`** ("clear this"), and a plain nullable Kotlin type can't tell
 * those apart once JSON has been decoded — both collapse to `null`. [PatchValue] keeps the two
 * cases distinct so [com.neverlate.backend.tasks.TaskService] can implement `PATCH /tasks/{id}`
 * (contract.md §3) correctly: e.g. `{"deadline": null}` clears the deadline, while a request that
 * omits `deadline` entirely leaves it as-is.
 *
 * Routes build this by inspecting the raw request JSON for key presence (see
 * `tasks/TaskRoutes.kt`) rather than relying on kotlinx.serialization's default-value decoding,
 * which cannot distinguish "absent" from "present but null" either.
 */
sealed class PatchValue<out T> {
    data object Absent : PatchValue<Nothing>()
    data class Present<T>(val value: T?) : PatchValue<T>()
}

/** Resolves a patch against the [current] stored value: unchanged if [PatchValue.Absent], the new
 *  (possibly null) value if [PatchValue.Present]. A top-level function (not a member of
 *  [PatchValue]) because [current]'s type `T?` would otherwise sit in a parameter ("in") position
 *  on a type declared `out` — fine for a free function, not for a class member. */
fun <T> PatchValue<T>.orElse(current: T?): T? = when (this) {
    PatchValue.Absent -> current
    is PatchValue.Present -> value
}
