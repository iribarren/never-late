package com.neverlate.backend.auth

import com.neverlate.backend.common.EmailTakenException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Test fake for [UserRepository]: no SQL, no Docker — just a thread-safe in-memory map. This is
 * the same "swap the real implementation for an in-memory one behind the seam" trick the Android
 * client uses for Room-backed repositories in its own tests.
 */
class InMemoryUserRepository : UserRepository {
    private val usersById = ConcurrentHashMap<Long, User>()
    private val nextId = AtomicLong(1)

    override fun findByEmail(email: String): User? = usersById.values.find { it.email == email }

    override fun findById(id: Long): User? = usersById[id]

    override fun create(email: String, passwordHash: String, createdAt: Long): User {
        if (findByEmail(email) != null) throw EmailTakenException()
        val user = User(id = nextId.getAndIncrement(), email = email, passwordHash = passwordHash, createdAt = createdAt)
        usersById[user.id] = user
        return user
    }
}
