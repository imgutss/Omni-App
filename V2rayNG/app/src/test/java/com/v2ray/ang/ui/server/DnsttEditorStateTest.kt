package com.v2ray.ang.ui.server

import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.fmt.DnsttFmt
import com.v2ray.ang.fmt.MasterDnsFmt
import com.v2ray.ang.fmt.SoarProfileFmt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DnsttEditorStateTest {
    @Test
    fun editorRoundTripKeepsOnlyDnsttFieldsOnDnsttProfiles() {
        val original = DnsttFmt.fromValues(
            "dns", "dot", "1.1.1.1:853", "t.example.com",
            "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff",
        )!!
        val state = ServerUiState.from(original)
        assertEquals("dot", state.dnsttTransport)
        assertEquals("1.1.1.1:853", state.dnsttResolver)
        assertEquals("t.example.com", state.dnsttDomain)
        assertEquals(DnsttFmt.DEFAULT_UTLS, state.dnsttUtls)

        val saved = state.toProfileItem(original)
        assertEquals(EConfigType.DNSTT, saved.configType)
        assertEquals(original.dnsttPublicKey, saved.dnsttPublicKey)
        assertNull(saved.masterDnsConfig)
        assertNull(saved.aetherCommand)
        assertEquals("t.example.com", saved.server)
    }

    @Test
    fun soarAndCottenEditorStateRetainImportedIdentityAndTuning() {
        val soar = ProfileItem.create(EConfigType.DNSTT).also {
            it.dnsttEngine = DnsttFmt.ENGINE_SOAR
            it.soarSettings = SoarProfileFmt.normalizeSettings("{\"transport\":\"only\",\"discoveryWant\":7}")
            it.dnsttTransport = "tcp"
        }
        val savedSoar = ServerUiState.from(soar).toProfileItem(soar)
        assertEquals(DnsttFmt.ENGINE_SOAR, savedSoar.dnsttEngine)
        assertEquals(soar.soarSettings, savedSoar.soarSettings)

        val cotten = ProfileItem.create(EConfigType.MASTERDNS).also {
            it.masterDnsEngine = MasterDnsFmt.ENGINE_COTTENDNS
            it.masterDnsConfig = "DOMAINS = [\"cot.example\"]"
        }
        val savedCotten = ServerUiState.from(cotten).toProfileItem(cotten)
        assertEquals(MasterDnsFmt.ENGINE_COTTENDNS, savedCotten.masterDnsEngine)
        assertEquals(cotten.masterDnsConfig, savedCotten.masterDnsConfig)
    }

    @Test
    fun newDnsttEditorUsesTheOfficialUtlsDefault() {
        val state = ServerUiState.from(com.v2ray.ang.dto.entities.ProfileItem.create(EConfigType.DNSTT))
        assertEquals("doh", state.dnsttTransport)
        assertEquals(DnsttFmt.DEFAULT_UTLS, state.dnsttUtls)
    }
}
