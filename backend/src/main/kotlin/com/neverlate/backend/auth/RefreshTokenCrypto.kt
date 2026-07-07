package com.neverlate.backend.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Mints the raw, opaque refresh token handed to the client (contract.md §2: "opaque, long-lived").
 * Unlike the access token (a structured, signature-verifiable JWT), a refresh token is just a
 * random string the server looks up by hash — there is nothing to decode, so its only requirement
 * is enough entropy that guessing it is infeasible. 256 bits (32 random bytes) comfortably clears
 * that bar; base64url keeps the result URL/header-safe with no padding characters to escape.
 */
object RefreshTokenGenerator {
    private const val TOKEN_BYTES = 32
    private val secureRandom = SecureRandom()

    fun generate(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

/**
 * Hashes a raw refresh token before it touches the database. The server never stores (or logs) a
 * refresh token in clear (contract.md §1.1) — only this hash — so a database leak alone does not
 * hand out usable tokens.
 *
 * This deliberately does **not** reuse [PasswordHasher]'s bcrypt: bcrypt's slowness defends a
 * low-entropy, human-chosen secret against offline brute-forcing. A refresh token already has 256
 * bits of machine-generated entropy, so there is nothing left for a slow KDF to protect against —
 * and refresh lookups happen on a hot path (every silent renewal), so a fast, deterministic
 * SHA-256 digest (which also enables an indexed equality lookup, unlike bcrypt's per-call salt) is
 * the standard, correct choice here.
 */
object RefreshTokenHasher {
    fun hash(rawToken: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(rawToken.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(digest)
    }
}
