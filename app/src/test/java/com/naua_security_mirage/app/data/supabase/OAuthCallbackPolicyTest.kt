package com.naua_security_mirage.app.data.supabase

import org.junit.Assert.assertTrue
import org.junit.Test

class OAuthCallbackPolicyTest {

    private val now = 1_789_000_000_000L
    private val ttl = 600_000L
    private val state = "expected-state"
    private val verifier = "verifier"

    private fun verdict(
        expected: String? = state,
        issuedAt: Long = now - 1_000,
        returned: String? = state,
        code: String? = "auth-code",
        ver: String? = verifier
    ) = OAuthCallbackPolicy.evaluate(expected, issuedAt, now, returned, code, ver, ttl)

    @Test
    fun acceptsAMatchingFreshCallback() {
        assertTrue(verdict() is OAuthCallbackPolicy.Verdict.Accept)
    }

    @Test
    fun acceptsACallbackThatCarriesNoState() {
        assertTrue(verdict(returned = null) is OAuthCallbackPolicy.Verdict.Accept)
        assertTrue(verdict(returned = "") is OAuthCallbackPolicy.Verdict.Accept)
    }

    @Test
    fun anInjectedCallbackWithoutStateStillNeedsTheVerifier() {
        assertTrue(verdict(returned = null, ver = null) is OAuthCallbackPolicy.Verdict.Reject)
        assertTrue(verdict(returned = null, code = null) is OAuthCallbackPolicy.Verdict.Reject)
    }

    @Test
    fun refusesAMismatchedState() {
        assertTrue(verdict(returned = "something-else") is OAuthCallbackPolicy.Verdict.Reject)
    }

    @Test
    fun refusesWhenNoSignInIsPending() {
        assertTrue(verdict(expected = null) is OAuthCallbackPolicy.Verdict.Reject)
        assertTrue(verdict(expected = "") is OAuthCallbackPolicy.Verdict.Reject)
    }

    @Test
    fun refusesAnExpiredOrFutureSignIn() {
        assertTrue(verdict(issuedAt = now - ttl - 1) is OAuthCallbackPolicy.Verdict.Reject)
        assertTrue(verdict(issuedAt = now + 60_000) is OAuthCallbackPolicy.Verdict.Reject)
    }

    @Test
    fun refusesACallbackWithoutACode() {
        assertTrue(verdict(code = null) is OAuthCallbackPolicy.Verdict.Reject)
    }

    @Test
    fun refusesACodeWithoutTheVerifier() {
        assertTrue(verdict(ver = null) is OAuthCallbackPolicy.Verdict.Reject)
        assertTrue(verdict(ver = "") is OAuthCallbackPolicy.Verdict.Reject)
    }

    @Test
    fun acceptsExactlyAtTheFreshnessBoundary() {
        val v = OAuthCallbackPolicy.evaluate(state, now - ttl, now, state, "code", verifier, ttl)
        assertTrue(v is OAuthCallbackPolicy.Verdict.Accept)
    }
}
