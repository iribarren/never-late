package com.neverlate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.neverlate.data.DataStoreUserPreferencesRepository
import com.neverlate.data.UserPreferencesRepository
import com.neverlate.data.articles.ArticleRepository
import com.neverlate.data.articles.LocalArticleRepository
import com.neverlate.ui.navigation.AppNavHost
import com.neverlate.ui.theme.NeverLateTheme

/**
 * Single entry point of the app. In Compose there is usually one Activity that hosts the whole
 * UI tree declared inside [setContent].
 *
 * The [UserPreferencesRepository] and [ArticleRepository] are created once here (manual
 * dependency injection — no framework needed for a project this size) and threaded down into
 * [AppNavHost], which passes each to whichever screen needs it via
 * [com.neverlate.ui.navigation.AppViewModelFactory].
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository: UserPreferencesRepository = DataStoreUserPreferencesRepository(applicationContext)
        val articleRepository: ArticleRepository = LocalArticleRepository(applicationContext)

        setContent {
            NeverLateTheme {
                AppNavHost(repository = repository, articleRepository = articleRepository)
            }
        }
    }
}
