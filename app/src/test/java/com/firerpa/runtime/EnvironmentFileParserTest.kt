package com.firerpa.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EnvironmentFileParserTest {
    @Test
    fun parseReadsPortAndCredential() {
        val content = """
            PORT=65000
            CREDENTIAL=secret-token
            USRDIR=/data/8IUDsB/usr
        """.trimIndent()

        val values = EnvironmentFileParser.parse(content)

        assertEquals("65000", values["PORT"])
        assertEquals("secret-token", values["CREDENTIAL"])
    }

    @Test
    fun parseTreatsEmptyCredentialAsBlank() {
        val values = EnvironmentFileParser.parse("PORT=65000\nCREDENTIAL=")

        assertEquals("65000", values["PORT"])
        assertEquals("", values["CREDENTIAL"])
    }

    @Test
    fun parseIgnoresInvalidLines() {
        val values = EnvironmentFileParser.parse("invalid-line\nPORT=65000")

        assertNull(values["invalid-line"])
        assertEquals("65000", values["PORT"])
    }
}
