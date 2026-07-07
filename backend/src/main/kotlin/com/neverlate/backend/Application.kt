package com.neverlate.backend

import com.neverlate.backend.auth.AuthService
import com.neverlate.backend.auth.PostgresRefreshTokenRepository
import com.neverlate.backend.auth.PostgresUserRepository
import com.neverlate.backend.auth.RefreshTokenRepository
import com.neverlate.backend.auth.UserRepository
import com.neverlate.backend.auth.authRoutes
import com.neverlate.backend.db.createDataSource
import com.neverlate.backend.db.initSchema
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

    embeddedServer(Netty, port = config.port) {
        configureApp(
            config,
            PostgresUserRepository(dataSource),
            PostgresTaskRepository(dataSource),
            PostgresRefreshTokenRepository(dataSource),
        )
    }.start(wait = true)
}

/** Wires plugins + routes given a [Config] and repositories. Split out from [main] so tests can
 *  call it with [com.neverlate.backend.auth.InMemoryUserRepository] /
 *  [com.neverlate.backend.tasks.InMemoryTaskRepository] /
 *  [com.neverlate.backend.auth.InMemoryRefreshTokenRepository] instead of a real database. */
fun Application.configureApp(
    config: Config,
    userRepository: UserRepository,
    taskRepository: TaskRepository,
    refreshTokenRepository: RefreshTokenRepository,
) {
    install(CallLogging)
    configureSerialization()
    configureErrorHandling()
    configureSecurity(config)

    val authService = AuthService(userRepository, refreshTokenRepository, config)
    val taskService = TaskService(taskRepository)

    routing {
        authRoutes(authService)
        taskRoutes(taskService)
    }
}
