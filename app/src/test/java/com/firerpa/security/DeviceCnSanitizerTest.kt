package com.firerpa.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCnSanitizerTest {
    @Test
    fun buildsBrandModelSerialCn() {
        assertEquals(
            "google_Pixel_8_Pro_ABC123",
            DeviceCnSanitizer.fromDeviceParts("google", "Pixel 8 Pro", "ABC123"),
        )
    }

    @Test
    fun omitsSerialWhenUnavailable() {
        assertEquals(
            "google_Pixel_8_Pro",
            DeviceCnSanitizer.fromDeviceParts("google", "Pixel 8 Pro", null),
        )
        assertEquals(
            "google_Pixel_8_Pro",
            DeviceCnSanitizer.fromDeviceParts("google", "Pixel 8 Pro", "unknown"),
        )
    }

    @Test
    fun replacesInvalidCharacters() {
        assertEquals(
            "samsung_Galaxy_S24_SN-001",
            DeviceCnSanitizer.fromDeviceParts("samsung", "Galaxy/S24+", "SN-001"),
        )
    }

    @Test
    fun collapsesRepeatedUnderscores() {
        assertEquals(
            "Google_A_B_X_Y",
            DeviceCnSanitizer.fromDeviceParts("Google", "A  /  B", "X  Y"),
        )
    }

    @Test
    fun usesFallbackForBlankParts() {
        assertEquals("FIRERPA_DEVICE", DeviceCnSanitizer.fromDeviceParts("   ", "   ", null))
    }

    @Test
    fun limitsCnLength() {
        val cn = DeviceCnSanitizer.fromDeviceParts("Brand", "A".repeat(100), "B".repeat(100))
        assertTrue(cn.length <= 64)
    }
}

class DeviceSerialNumberTest {
    @Test
    fun rejectsUnknownSerialValues() {
        assertFalse(DeviceSerialNumber.isUsableSerial("unknown"))
        assertFalse(DeviceSerialNumber.isUsableSerial("UNKNOWN"))
        assertFalse(DeviceSerialNumber.isUsableSerial(""))
        assertTrue(DeviceSerialNumber.isUsableSerial("ABC123"))
    }
}
