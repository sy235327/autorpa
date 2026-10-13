package com.firerpa.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceNetworkAddressesTest {
    @Test
    fun parseAddressLinesClassifiesInternalAndExternal() {
        val (internal, external) = DeviceNetworkAddresses.parseAddressLines(
            sequenceOf("192.168.1.23", "10.0.0.5", "8.8.8.8", "127.0.0.1", ""),
        )

        assertEquals(listOf("10.0.0.5", "192.168.1.23"), internal)
        assertEquals(listOf("8.8.8.8"), external)
    }
}
