package com.neverlate.backend.auth

/**
 * The repository seam for user accounts — mirrors the client app's `TaskRepository` pattern
 * (see CLAUDE.md): callers (AuthService) depend only on this interface, never on SQL or a
 * specific store. [PostgresUserRepository] is the real implementation (used via docker-compose);
 * [InMemoryUserRepository] is a fake used by tests, so the test suite never needs Docker/Postgres
 * to run.
 */
interface UserRepository {
    fun findByEmail(email: String): User?
    fun findById(id: Long): User?

    /** Creates a new user. Throws [com.neverlate.backend.common.EmailTakenException] if the
     *  email is already registered — checked at the repository level so the create is atomic
     *  with the uniqueness check (no separate find-then-insert race). */
    fun create(email: String, passwordHash: String, createdAt: Long): User
}
