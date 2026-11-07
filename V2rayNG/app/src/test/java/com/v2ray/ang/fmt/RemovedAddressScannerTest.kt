package com.v2ray.ang.fmt

import com.google.gson.Gson
import com.v2ray.ang.dto.entities.ProfileItem
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RemovedAddressScannerTest {
    @Test
    fun oldSavedOverridesAreIgnoredWithoutChangingTheOriginalProfile() {
        for (type in listOf("VLESS", "TROJAN")) {
            // Even invalid multiline values saved through the old editor must be harmless.
            val original = """{"configType":"$type","server":"origin.example.com","serverPort":"8443","password":"secret","sni":"tls.example.com","host":"http.example.com","network":"ws","path":"/original"}"""
            val old = original.dropLast(1) + ""","dialAddress":"192.0.2.1\n192.0.2.2"}"""
            val gson = Gson()
            val clean = gson.fromJson(original, ProfileItem::class.java)
            val upgraded = gson.fromJson(old, ProfileItem::class.java)
            assertEquals(clean, upgraded)
            assertFalse(gson.toJson(upgraded).contains("dialAddress"))
        }
    }

    @Test
    fun legacyLinkOverridesAreIgnoredAndNeverSharedAgain() {
        val query = "security=tls&type=ws&sni=tls.example.com&host=http.example.com&path=%2Foriginal"
        val vless = "vless://e404d4a2-1166-42ae-a37d-13454e6a27e6@origin.example.com:8443?$query"
        val trojan = "trojan://secret@origin.example.com:8443?$query"
        val legacySuffix = "&dialAddress=192.0.2.1%0A192.0.2.2"
        val vp = VlessFmt.parse(vless + legacySuffix)!!
        val tp = TrojanFmt.parse(trojan + legacySuffix)
        assertEquals(VlessFmt.parse(vless)!!.copy(addedTime = 0), vp.copy(addedTime = 0))
        assertEquals(TrojanFmt.parse(trojan).copy(addedTime = 0), tp.copy(addedTime = 0))
        assertFalse(VlessFmt.toUri(vp).contains("dialAddress"))
        assertFalse(TrojanFmt.toUri(tp).contains("dialAddress"))
    }
}
