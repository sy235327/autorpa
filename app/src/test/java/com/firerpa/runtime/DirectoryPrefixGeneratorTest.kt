package com.firerpa.runtime

import com.firerpa.data.AppPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectoryPrefixGeneratorTest {
    @Test
    fun generateProducesSixAlphanumericCharacters() {
        repeat(20) {
            val prefix = DirectoryPrefixGenerator.generate()
            assertEquals(6, prefix.length)
            assertTrue(AppPrefs.PREFIX_PATTERN.matches(prefix))
        }
    }
}
