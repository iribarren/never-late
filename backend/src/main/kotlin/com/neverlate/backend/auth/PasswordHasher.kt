package com.neverlate.backend.auth

import org.mindrot.jbcrypt.BCrypt

/**
 * Wraps bcrypt so the rest of the codebase never touches a raw password/hash directly.
 * bcrypt (unlike a plain fast hash such as SHA-256) is deliberately slow and includes a random
 * per-password salt baked into its output string — that's what makes it suitable for passwords:
 * it resists both rainbow-table lookups (salt) and brute-force GPU cracking (cost factor).
 */
object PasswordHasher {
    /** `gensalt()`'s default cost factor (10) — the number of bcrypt rounds is 2^cost. Higher is
     *  slower to brute-force but also slower to verify; 10 is a reasonable default in 2026. */
    fun hash(password: String): String = BCrypt.hashpw(password, BCrypt.gensalt())

    fun verify(password: String, hash: String): Boolean = BCrypt.checkpw(password, hash)
}
