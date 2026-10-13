package com.firerpa.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceIniDocumentTest {
    private val sampleIni = """
        [DEFAULT]
        port=65000
    """.trimIndent()

    @Test
    fun readAuthSettingsDisabledWhenSslCredentialMissing() {
        val settings = ServiceIniDocument.readAuthSettings(sampleIni)
        assertFalse(settings.enabled)
        assertEquals("", settings.password)
    }

    @Test
    fun readAuthSettingsEnabledWhenSslCredentialPresent() {
        val ini = """
            [DEFAULT]
            port=65000
            ssl-web-credential=abc123
        """.trimIndent()
        val settings = ServiceIniDocument.readAuthSettings(ini)
        assertTrue(settings.enabled)
        assertEquals("abc123", settings.password)
    }

    @Test
    fun applyAuthSettingsAppendsCertAndSslCredentialToDefaultSection() {
        val merged = ServiceIniDocument.applyAuthSettings(
            raw = sampleIni,
            enabled = true,
            password = "secret",
            clientPemBase64 = "Y2xpZW50LWNlcnQ=",
        )
        val settings = ServiceIniDocument.readAuthSettings(merged)
        assertTrue(settings.enabled)
        assertEquals("secret", settings.password)
        assertTrue(merged.contains("cert=Y2xpZW50LWNlcnQ="))
        assertTrue(merged.indexOf("port=") < merged.indexOf("cert="))
        assertTrue(merged.indexOf("cert=") < merged.indexOf("ssl-web-credential="))
    }

    @Test
    fun applyAuthSettingsRemovesDefaultAuthKeysWhenDisabled() {
        val ini = """
            [DEFAULT]
            port=65000
            cert=applied-cert
            ssl-web-credential=applied-pass
        """.trimIndent()
        val merged = ServiceIniDocument.applyAuthSettings(
            raw = ServiceIniDocument.toEditorContent(ini),
            enabled = false,
            password = "",
            clientPemBase64 = "",
        )
        val settings = ServiceIniDocument.readAuthSettings(merged)
        assertFalse(settings.enabled)
        assertEquals("", settings.password)
        assertFalse(Regex("""(?m)^cert=applied-cert$""").containsMatchIn(merged))
        assertFalse(Regex("""(?m)^ssl-web-credential=applied-pass$""").containsMatchIn(merged))
        assertTrue(merged.contains("port=65000"))
    }

    @Test
    fun applyAuthSettingsDoesNotTouchOtherSections() {
        val ini = """
            [OTHER]
            cert=keep-cert
            ssl-web-credential=keep-pass
            [DEFAULT]
            port=65000
        """.trimIndent()
        val merged = ServiceIniDocument.applyAuthSettings(
            raw = ini,
            enabled = true,
            password = "secret",
            clientPemBase64 = "bmV3LWNlcnQ=",
        )
        assertTrue(merged.contains("[OTHER]"))
        assertTrue(merged.contains("cert=keep-cert"))
        assertTrue(merged.contains("ssl-web-credential=keep-pass"))
        assertTrue(merged.contains("cert=bmV3LWNlcnQ="))
    }

    @Test
    fun previewEditorContentShowsAuthKeysWhenEnabled() {
        val preview = ServiceIniDocument.previewEditorContent(
            raw = sampleIni,
            authEnabled = true,
            password = "secret",
            clientPemBase64 = "Y2xpZW50LWNlcnQ=",
        )
        assertTrue(preview.contains("cert=Y2xpZW50LWNlcnQ="))
        assertTrue(preview.contains("ssl-web-credential=secret"))
    }

    @Test
    fun previewEditorContentHidesAuthKeysWhenDisabled() {
        val ini = """
            [DEFAULT]
            cert=hidden
            ssl-web-credential=hidden
            port=65000
        """.trimIndent()
        val preview = ServiceIniDocument.previewEditorContent(
            raw = ini,
            authEnabled = false,
            password = "",
            clientPemBase64 = "",
        )
        assertFalse(preview.contains("cert="))
        assertFalse(preview.contains("ssl-web-credential="))
        assertTrue(preview.contains("port=65000"))
    }

    @Test
    fun toEditorContentStripsDefaultAuthKeysOnly() {
        val ini = """
            [OTHER]
            ssl-web-credential=keep
            [DEFAULT]
            port=65000
            cert=secret-cert
            ssl-web-credential=secret
        """.trimIndent()
        val editor = ServiceIniDocument.toEditorContent(ini)
        assertFalse(editor.contains("cert=secret-cert"))
        assertFalse(Regex("""(?m)^ssl-web-credential=secret$""").containsMatchIn(editor))
        assertTrue(editor.contains("port=65000"))
        assertTrue(editor.contains("[OTHER]"))
        assertTrue(editor.contains("ssl-web-credential=keep"))
    }

    @Test
    fun hasEditorChangesComparesEditableContentOnly() {
        val original = """
            [OTHER]
            cert=keep-cert
            [DEFAULT]
            port=65000
            cert=stored-cert
            ssl-web-credential=stored-pass
        """.trimIndent()
        val unchangedEditor = ServiceIniDocument.toEditorContent(original)
        assertFalse(ServiceIniDocument.hasEditorChanges(original, unchangedEditor))
        val changedEditor = """
            [OTHER]
            cert=keep-cert
            [DEFAULT]
            port=65001
        """.trimIndent()
        assertTrue(ServiceIniDocument.hasEditorChanges(original, changedEditor))
    }

    @Test
    fun mergeEditorPreservingAuthKeysReturnsOriginalWhenUnchanged() {
        val original = """
            [OTHER]
            cert=keep-cert
            [DEFAULT]
            port=65000
            cert=stored-cert
            ssl-web-credential=stored-pass
        """.trimIndent()
        val editor = ServiceIniDocument.toEditorContent(original)
        assertEquals(original, ServiceIniDocument.mergeEditorPreservingAuthKeys(editor, original))
    }

    @Test
    fun mergeEditorPreservingAuthKeysUpdatesOnlyNonAuthKeys() {
        val original = """
            [OTHER]
            cert=keep-cert
            [DEFAULT]
            port=65000
            cert=stored-cert
            ssl-web-credential=stored-pass
        """.trimIndent()
        val editor = """
            [OTHER]
            cert=keep-cert
            [DEFAULT]
            port=65001
        """.trimIndent()
        val merged = ServiceIniDocument.mergeEditorPreservingAuthKeys(editor, original)
        assertTrue(merged.contains("port=65001"))
        assertTrue(merged.contains("cert=stored-cert"))
        assertTrue(merged.contains("ssl-web-credential=stored-pass"))
        assertTrue(merged.contains("cert=keep-cert"))
    }

    @Test
    fun requiresForcedAccessAuthenticationWhenNonDefaultSectionHasCert() {
        val ini = """
            [OTHER]
            cert=keep-cert
            [DEFAULT]
            port=65000
        """.trimIndent()
        assertTrue(ServiceIniDocument.requiresForcedAccessAuthentication(ini))
    }

    @Test
    fun requiresForcedAccessAuthenticationIgnoresDefaultSectionCert() {
        val ini = """
            [DEFAULT]
            cert=default-cert
            port=65000
        """.trimIndent()
        assertFalse(ServiceIniDocument.requiresForcedAccessAuthentication(ini))
    }

    @Test
    fun isValidAuthPasswordEnforcesLengthRange() {
        assertFalse(ServiceIniDocument.isValidAuthPassword(""))
        assertFalse(ServiceIniDocument.isValidAuthPassword("1234"))
        assertTrue(ServiceIniDocument.isValidAuthPassword("12345"))
        assertTrue(ServiceIniDocument.isValidAuthPassword("a".repeat(32)))
        assertFalse(ServiceIniDocument.isValidAuthPassword("a".repeat(33)))
    }
}
