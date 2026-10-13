package com.firerpa.runtime

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address
import java.net.NetworkInterface

object DeviceNetworkAddresses {
    fun localIPv4Addresses(context: Context): Pair<List<String>, List<String>> {
        val internal = linkedSetOf<String>()
        val external = linkedSetOf<String>()

        collectFromConnectivityManager(context, internal, external)
        if (internal.isEmpty() && external.isEmpty()) {
            collectFromNetworkInterfaces(internal, external)
        }

        return internal.sorted() to external.sorted()
    }

    fun parseAddressLines(lines: Sequence<String>): Pair<List<String>, List<String>> {
        val internal = linkedSetOf<String>()
        val external = linkedSetOf<String>()

        lines.map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("127.") }
            .forEach { host ->
                classifyHost(host, internal, external)
            }

        return internal.sorted() to external.sorted()
    }

    @Suppress("DEPRECATION")
    private fun collectFromConnectivityManager(
        context: Context,
        internal: MutableSet<String>,
        external: MutableSet<String>,
    ) {
        runCatching {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager.allNetworks.forEach { network ->
                val linkProperties = connectivityManager.getLinkProperties(network) ?: return@forEach
                linkProperties.linkAddresses.forEach addressLoop@{ linkAddress ->
                    val address = linkAddress.address
                    if (address !is Inet4Address || address.isLoopbackAddress) {
                        return@addressLoop
                    }
                    classifyHost(address.hostAddress.orEmpty(), internal, external)
                }
            }
        }
    }

    private fun collectFromNetworkInterfaces(
        internal: MutableSet<String>,
        external: MutableSet<String>,
    ) {
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().forEach { networkInterface ->
                if (!networkInterface.isUp || networkInterface.isLoopback) {
                    return@forEach
                }

                networkInterface.inetAddresses.toList().forEach addressLoop@{ address ->
                    if (address !is Inet4Address || address.isLoopbackAddress) {
                        return@addressLoop
                    }
                    classifyHost(address.hostAddress.orEmpty(), internal, external)
                }
            }
        }
    }

    private fun classifyHost(
        host: String,
        internal: MutableSet<String>,
        external: MutableSet<String>,
    ) {
        val normalized = host.trim()
        if (normalized.isEmpty() || normalized.startsWith("127.")) {
            return
        }

        val siteLocal = runCatching {
            Inet4Address.getByName(normalized).isSiteLocalAddress
        }.getOrDefault(normalized.startsWith("192.168.") ||
            normalized.startsWith("10.") ||
            normalized.startsWith("172."))

        if (siteLocal) {
            internal.add(normalized)
        } else {
            external.add(normalized)
        }
    }
}
