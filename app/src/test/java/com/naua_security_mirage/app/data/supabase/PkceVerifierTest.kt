package com.naua_security_mirage.app.data.supabase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PkceVerifierTest {

    @Test
    fun everyPossibleByteValueStaysInsideTheAlphabet() {
        val all = ByteArray(256) { it.toByte() }
        val verifier = PkceVerifier.fromBytes(all)
        assertEquals(256, verifier.length)
        assertTrue(
            "verifier left the unreserved set: " + verifier.filter { it !in PkceVerifier.ALPHABET },
            verifier.all { it in PkceVerifier.ALPHABET }
        )
    }

    @Test
    fun theHighHalfOfTheByteRangeIsHandledNotSkipped() {

        val high = ByteArray(128) { (0x80 + it).toByte() }
        val verifier = PkceVerifier.fromBytes(high)
        assertEquals(128, verifier.length)
        assertTrue(verifier.all { it in PkceVerifier.ALPHABET })
    }

    @Test
    fun theAlphabetIsTheUnreservedSetRfc7636Allows() {
        assertEquals(66, PkceVerifier.ALPHABET.length)
        assertEquals(
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~",
            PkceVerifier.ALPHABET
        )
    }

    @Test
    fun lengthIsWithinTheRangeRfc7636Requires() {
        val verifier = PkceVerifier.fromBytes(ByteArray(64))
        assertTrue(verifier.length in 43..128)
    }

    @Test
    fun zeroesAndEmptyInputDoNotThrow() {
        assertEquals(0, PkceVerifier.fromBytes(ByteArray(0)).length)
        assertEquals(1, PkceVerifier.fromBytes(byteArrayOf(0)).length)
        assertTrue(PkceVerifier.fromBytes(ByteArray(64) { 0 })[0] in PkceVerifier.ALPHABET)
    }
}
