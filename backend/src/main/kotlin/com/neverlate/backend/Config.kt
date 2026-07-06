package com.neverlate.backend

/**
 * All configuration comes from environment variables — never hardcoded, never committed.
 * `backend/.env.example` documents every variable with a safe placeholder; `docker-compose.yml`
 * loads the real `.env` (gitignored) into the container's environment.
 *
 * This is the concrete form of the project rule "sensitive logic/config lives on the backend,
 * out of source control": the JWT signing secret and DB credentials never appear in code.
 */
data class Config(
    val port: Int,
    val jwtSecret: String,
    val jwtIssuer: String,
    val jwtAudience: String,
    val jwtExpiryHours: Long,
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
) {
    companion object {
        /** Reads config from the process environment, applying sane local-dev defaults where
         *  doing so is safe (port, issuer/audience, expiry) but *requiring* secrets to be set
         *  explicitly (JWT_SECRET, DB credentials) — a missing secret should fail loudly at
         *  startup, not silently fall back to something guessable. */
        fun fromEnv(): Config {
            fun required(name: String): String =
                System.getenv(name)
                    ?: error("Missing required environment variable: $name (see backend/.env.example)")

            return Config(
                port = System.getenv("PORT")?.toIntOrNull() ?: 8080,
                jwtSecret = required("JWT_SECRET"),
                jwtIssuer = System.getenv("JWT_ISSUER") ?: "never-late-backend",
                jwtAudience = System.getenv("JWT_AUDIENCE") ?: "never-late-app",
                jwtExpiryHours = System.getenv("JWT_EXPIRY_HOURS")?.toLongOrNull() ?: 24L,
                databaseUrl = required("DATABASE_URL"),
                databaseUser = required("DATABASE_USER"),
                databasePassword = required("DATABASE_PASSWORD"),
            )
        }
    }
}
