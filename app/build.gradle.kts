import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

// Dev-environment fix (bugfix/backend-url-dispositivo-fisico): makes the backend base URL
// configurable per developer without editing Kotlin source or committing anyone's network
// details. `local.properties` is untracked (see .gitignore), so it's the right place for a
// personal value; unlike `gradle.properties`, it never gets committed. Gradle does NOT expose
// local.properties to the build script automatically, so it's loaded by hand here. A property
// named "neverlate.backendBaseUrl" wins if present; otherwise we fall back to a same-named
// Gradle/project property (e.g. from gradle.properties), and finally to the emulator's
// host-loopback alias so a clean checkout keeps working with zero configuration.
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        FileInputStream(localPropertiesFile).use { load(it) }
    }
}
val backendBaseUrl: String = localProperties.getProperty("neverlate.backendBaseUrl")
    ?: findProperty("neverlate.backendBaseUrl") as String?
    ?: "http://10.0.2.2:8080/"

android {
    namespace = "com.neverlate"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.neverlate"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Injects the backend base URL computed above into BuildConfig.BACKEND_BASE_URL, read by
        // BackendNetwork.DEFAULT_BACKEND_BASE_URL. Defined in defaultConfig (not gated to debug)
        // so every build type gets a value; release builds keep the same emulator-alias default
        // since no developer sets this property for a release build.
        buildConfigField("String", "BACKEND_BASE_URL", "\"$backendBaseUrl\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Back-ports the java.time APIs (used for feature 08's locale-aware date formatting) so
        // they run on minSdk = 24; without this they would require API 26.
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // Generates BuildConfig.DEBUG, used by ArticlesNetwork to gate verbose OkHttp body
        // logging to debug builds only (feature 10).
        buildConfig = true
    }

    testOptions {
        // Robolectric (feature 11) needs the merged manifest/resources to build its simulated
        // Android environment for the OutboxTaskRepository/SyncEngine Room tests.
        unitTests.isIncludeAndroidResources = true
    }

    // Ships the exported Room schema JSONs (see the `ksp` block below) as androidTest assets so
    // MigrationTestHelper can load them on-device to build the old-version database (feature 13b).
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

// Tells Room's KSP processor where to export the schema JSON snapshots that `exportSchema = true`
// (feature 13b) produces — one `N.json` per @Database version. MigrationTestHelper reads these to
// build an old-version database and migrate it forward, so the files are committed to the repo
// (`app/schemas/`). Wired here rather than via the Room Gradle plugin to keep the build config
// minimal; the directory is also added to androidTest assets so the helper can find it at runtime.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    // Feature 13c: Room's Paging integration, needed for ArticleDao to return a
    // PagingSource<Int, ArticleEntity> directly (Room/KSP generates the query implementation).
    implementation(libs.androidx.room.paging)
    // Feature 13c: Jetpack Paging 3 — paging-runtime is the core Pager/PagingSource/RemoteMediator
    // machinery, paging-compose adds collectAsLazyPagingItems()/LazyPagingItems for the UI layer.
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)
    // Glance: declarative API (Compose-like, but a restricted subset) for building App Widgets —
    // the home-screen pending-tasks widget (feature 05) is this project's first use of it.
    implementation(libs.androidx.glance.appwidget)
    // WorkManager: schedules the widget's periodic background refresh (feature 05), since a
    // widget has no `delay`-based ticker of its own — see WidgetRefreshWorker.
    implementation(libs.androidx.work.runtime.ktx)
    // Enables the java.time APIs on minSdk = 24 (see compileOptions.isCoreLibraryDesugaringEnabled).
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // Networking (feature 10): Retrofit is the typed HTTP client; OkHttp is the engine underneath
    // it (and the one whose logging-interceptor prints requests/responses for the lesson); the
    // kotlinx.serialization converter lets Retrofit deserialize JSON with the same library the
    // project already uses (see kotlinx.serialization.json above) instead of adding Moshi/Gson.
    implementation(libs.retrofit)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.retrofit.kotlinx.serialization.converter)

    // Remote DB + sync (feature 11): EncryptedSharedPreferences (Keystore-backed) is where the
    // auth token lives — see com.neverlate.data.auth.TokenStorage for why it must not be the
    // plaintext DataStore used for theme/reminder preferences.
    implementation(libs.androidx.security.crypto)

    // Dependency injection (feature 13d): Hilt replaces the manual AppViewModelFactory/MainActivity
    // wiring with a generated object graph. hilt-compiler runs through KSP (see the version catalog
    // comment on `hilt`), the same annotation-processing pipeline Room already uses, rather than
    // adding a second one (kapt) alongside it. hilt-navigation-compose is the small extra artifact
    // that adds hiltViewModel() for Compose + Navigation.
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Compose BOM: aligns all Compose library versions.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    // Feature 18b: WindowSizeClass (compact/medium/expanded), obtained once in MainActivity and
    // threaded down through AppNavHost — see the version catalog comment (BOM-managed, no version).
    implementation(libs.androidx.material3.windowsizeclass)
    // Feature 18b: the two-pane list-detail scaffold for the expanded-width Articles screen (see
    // ArticlesListDetailPane) — versioned independently of the BOM above, see the version catalog's
    // `composeMaterial3Adaptive` pin comment for why 1.0.0, not the newest release.
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.material3.adaptive.layout)
    implementation(libs.androidx.compose.material3.adaptive.navigation)
    // Only Icons.Filled.Pause (used for the task countdown's pause button) lives outside the
    // small default icon set bundled with material3/material-icons-core, hence this extra
    // dependency: it is versioned by the Compose BOM above, same as androidx.material3.
    implementation(libs.androidx.material.icons.extended)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // MockWebServer: a fake HTTP server for testing CachingArticleRepository/ArticlesApi without
    // touching the real network (feature 10).
    testImplementation(libs.okhttp.mockwebserver)
    // Robolectric (feature 11): a real in-memory Room database (with real transactions) for
    // OutboxTaskRepository/SyncEngine tests — see the version catalog comment on `robolectric`.
    testImplementation(libs.robolectric)
    // Feature 13c test coverage: asSnapshot() for testing ArticlesRemoteMediator/
    // CachingArticleRepository/ArticlesViewModel's Flow<PagingData<T>> without a Compose collector.
    testImplementation(libs.androidx.paging.testing)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    // MigrationTestHelper (feature 13b): drives a real Room migration against the exported schemas
    // to prove data survives the 4 -> 5 upgrade. Instrumented, so it lands in androidTest.
    androidTestImplementation(libs.androidx.room.testing)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
