package com.neverlate

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Feature 13d: the app's [Application] subclass, annotated `@HiltAndroidApp`.
 *
 * This annotation is what triggers Hilt's code generation: it builds the root
 * `SingletonComponent` — the top of the dependency graph every `@Module` in `di/` contributes
 * to, and every `@AndroidEntryPoint` class (currently just [MainActivity]) and `@HiltViewModel`
 * (every screen's ViewModel) ultimately gets its dependencies from. Before this feature, that same
 * "one graph for the whole process" role was played by hand: [NeverLateApplication] itself does
 * nothing here — it exists only to carry the annotation — whereas the equivalent manual wiring
 * used to live imperatively in `MainActivity.onCreate` (see that class's KDoc for the "before").
 *
 * Registered in `AndroidManifest.xml` via `android:name=".NeverLateApplication"`, exactly like any
 * other custom `Application` subclass — Hilt needs no other manifest entry.
 */
@HiltAndroidApp
class NeverLateApplication : Application()
