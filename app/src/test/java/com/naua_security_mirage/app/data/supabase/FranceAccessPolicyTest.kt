package com.naua_security_mirage.app.data.supabase

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FranceAccessPolicyTest {

    @Test
    fun acceptsAUuidIssuedForTheAccount() {
        assertTrue(FranceAccessPolicy.mayBuildPaidTunnel("99f4a4d9-6ea6-45d0-95d4-ba1d6c881bc4"))
    }

    @Test
    fun refusesWhenTheServerIssuedNothing() {
        assertFalse(FranceAccessPolicy.mayBuildPaidTunnel(null))
        assertFalse(FranceAccessPolicy.mayBuildPaidTunnel(""))
        assertFalse(FranceAccessPolicy.mayBuildPaidTunnel("   "))
    }

    @Test
    fun refusesAValueThatIsNotAUuid() {
        assertFalse(FranceAccessPolicy.mayBuildPaidTunnel("not-a-uuid"))
        assertFalse(FranceAccessPolicy.mayBuildPaidTunnel("99f4a4d9"))
        assertFalse(FranceAccessPolicy.mayBuildPaidTunnel("00000000-0000-0000-0000-000000000000" + "x"))
    }

    @Test
    fun acceptsAUuidTakenFromAVlessLink() {
        val link = "vless://99f4a4d9-6ea6-45d0-95d4-ba1d6c881bc4@host:443?type=tcp&security=reality"
        assertTrue(FranceAccessPolicy.mayBuildPaidTunnel(link))
    }

    @Test
    fun refusesALinkWithNoUuid() {
        assertFalse(FranceAccessPolicy.mayBuildPaidTunnel("vless://@host:443?type=tcp"))
        assertFalse(FranceAccessPolicy.mayBuildPaidTunnel("vless://?type=tcp"))
    }
}
