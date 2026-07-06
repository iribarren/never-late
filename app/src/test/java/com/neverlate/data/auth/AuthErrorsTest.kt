package com.neverlate.data.auth

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure JVM tests for [mapAuthErrorHttpCode] — no network/Retrofit involved. */
class AuthErrorsTest {

    @Test
    fun `409 maps to EMAIL_TAKEN`() {
        assertEquals(AuthErrorType.EMAIL_TAKEN, mapAuthErrorHttpCode(409))
    }

    @Test
    fun `401 maps to INVALID_CREDENTIALS`() {
        assertEquals(AuthErrorType.INVALID_CREDENTIALS, mapAuthErrorHttpCode(401))
    }

    @Test
    fun `400 maps to VALIDATION`() {
        assertEquals(AuthErrorType.VALIDATION, mapAuthErrorHttpCode(400))
    }

    @Test
    fun `403 (an otherwise-unmapped code) maps to UNKNOWN`() {
        assertEquals(AuthErrorType.UNKNOWN, mapAuthErrorHttpCode(403))
    }

    @Test
    fun `404 maps to UNKNOWN`() {
        assertEquals(AuthErrorType.UNKNOWN, mapAuthErrorHttpCode(404))
    }

    @Test
    fun `a 5xx server error maps to UNKNOWN`() {
        assertEquals(AuthErrorType.UNKNOWN, mapAuthErrorHttpCode(500))
    }
}
