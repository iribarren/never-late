package com.neverlate.backend

import com.neverlate.backend.articles.ArticleRepository
import com.neverlate.backend.articles.ArticleService
import com.neverlate.backend.articles.PostgresArticleRepository
import com.neverlate.backend.articles.articleRoutes
import com.neverlate.backend.auth.AuthService
import com.neverlate.backend.auth.PostgresRefreshTokenRepository
import com.neverlate.backend.auth.PostgresUserRepository
import com.neverlate.backend.auth.RefreshTokenRepository
import com.neverlate.backend.auth.UserRepository
import com.neverlate.backend.auth.authRoutes
import com.neverlate.backend.db.createDataSource
import com.neverlate.backend.db.initSchema
import com.neverlate.backend.db.seedArticlesIfEmpty
import com.neverlate.backend.plugins.configureErrorHandling
import com.neverlate.backend.plugins.configureSecurity
import com.neverlate.backend.plugins.configureSerialization
import com.neverlate.backend.tasks.PostgresTaskRepository
import com.neverlate.backend.tasks.TaskRepository
import com.neverlate.backend.tasks.TaskService
import com.neverlate.backend.tasks.taskRoutes
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.routing.routing

/**
 * Process entry point: reads config from the environment (Config.fromEnv — fails fast if a
 * secret is missing), opens the real Postgres connection pool, makes sure the schema exists, and
 * starts Netty. Kept tiny on purpose: everything that can be exercised without a real network
 * server lives in [configureApp], which tests call directly with fake repositories (see
 * src/test/.../ApplicationTestSupport.kt).
 */
fun main() {
    val config = Config.fromEnv()
    val dataSource = createDataSource(config)
    initSchema(dataSource)
    seedArticlesIfEmpty(dataSource)

    embeddedServer(Netty, port = config.port) {
        configureApp(
            config,
            PostgresUserRepository(dataSource),
            PostgresTaskRepository(dataSource),
            PostgresRefreshTokenRepository(dataSource),
            PostgresArticleRepository(dataSource),
        )
    }.start(wait = true)
}

/** Wires plugins + routes given a [Config] and repositories. Split out from [main] so tests can
 *  call it with [com.neverlate.backend.auth.InMemoryUserRepository] /
 *  [com.neverlate.backend.tasks.InMemoryTaskRepository] /
 *  [com.neverlate.backend.auth.InMemoryRefreshTokenRepository] /
 *  [com.neverlate.backend.articles.InMemoryArticleRepository] instead of a real database. */
fun Application.configureApp(
    config: Config,
    userRepository: UserRepository,
    taskRepository: TaskRepository,
    refreshTokenRepository: RefreshTokenRepository,
    articleRepository: ArticleRepository,
) {
    install(CallLogging)
    configureSerialization()
    configureErrorHandling()
    configureSecurity(config)

    val authService = AuthService(userRepository, refreshTokenRepository, config)
    val taskService = TaskService(taskRepository)
    val articleService = ArticleService(articleRepository)

    routing {
        authRoutes(authService)
        taskRoutes(taskService)
        // Public — deliberately registered outside any authenticate("auth-jwt") block
        // (contract.md §7): the article catalog must be readable in guest mode (feature 13).
        articleRoutes(articleService)
    }
}
