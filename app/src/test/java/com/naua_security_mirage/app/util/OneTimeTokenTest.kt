package com.naua_security_mirage.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OneTimeTokenTest {

    @Test
    fun rejectsForgedAndEmptyTokens() {
        assertFalse(OneTimeToken.consume(null))
        assertFalse(OneTimeToken.consume(""))
        assertFalse(OneTimeToken.consume("forged"))
    }

    @Test
    fun acceptsIssuedTokenOnlyOnce() {
        val token = OneTimeToken.issue()
        assertTrue(OneTimeToken.consume(token))
        assertFalse(OneTimeToken.consume(token))
    }

    @Test
    fun newIssueInvalidatesPreviousToken() {
        val first = OneTimeToken.issue()
        val second = OneTimeToken.issue()
        assertFalse(OneTimeToken.consume(first))
        assertTrue(OneTimeToken.consume(second))
    }
}
