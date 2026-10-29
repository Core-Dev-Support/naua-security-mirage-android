package com.naua_security_mirage.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerVersionTest {

    private fun newer(latest: String, current: String) =
        VersionComparator.isNewer(latest, current)

    @Test
    fun releaseIsNewerThanBetaWithSameVersionCode() {
        assertTrue(newer("v1.5.0", "1.5.0-beta"))
        assertFalse(newer("v1.5.0-beta", "1.5.0"))
    }

    @Test
    fun betaIsNewerThanPreviousRelease() {
        assertTrue(newer("v1.5.0-beta", "1.4.0"))
    }

    @Test
    fun newerBetaBuildIsDetected() {
        assertTrue(newer("v1.5.0-beta.2", "1.5.0-beta"))
        assertFalse(newer("v1.5.0-beta", "1.5.0-beta.2"))
    }

    @Test
    fun identicalVersionsAreNotNewer() {
        assertFalse(newer("v1.5.0", "1.5.0"))
        assertFalse(newer("1.5.0-beta", "1.5.0-beta"))
    }

    @Test
    fun numericComparisonStillWorks() {
        assertTrue(newer("1.10.0", "1.9.9"))
        assertFalse(newer("1.9.9", "1.10.0"))
    }
}
