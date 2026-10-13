package com.firerpa.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeServicePathsTest {
    @Test
    fun shellPathsIncludeRandomPrefix() {
        val prefix = "Ab3xYz"
        assertEquals(
            "/data/local/tmp/Ab3xYz/server",
            NativeServicePaths.serverRoot(ExecutionIdentity.SHIZUKU_SHELL, prefix),
        )
        assertEquals(
            "/data/local/tmp/Ab3xYz/usr",
            NativeServicePaths.usrRoot(ExecutionIdentity.SHIZUKU_SHELL, prefix),
        )
        assertEquals(
            "/data/local/tmp/Ab3xYz/server/bin/launch.sh",
            NativeServicePaths.launchPath(ExecutionIdentity.SHIZUKU_SHELL, prefix),
        )
        assertEquals(
            "/data/local/tmp/Ab3xYz/usr/.local",
            NativeServicePaths.configDir(ExecutionIdentity.SHIZUKU_SHELL, prefix),
        )
        assertEquals(
            "/data/local/tmp/Ab3xYz/usr/.local/properties",
            NativeServicePaths.configPath(ExecutionIdentity.SHIZUKU_SHELL, prefix),
        )
        assertEquals(
            "/data/local/tmp/Ab3xYz/usr/.local/run/environment",
            NativeServicePaths.environmentPath(ExecutionIdentity.SHIZUKU_SHELL, prefix),
        )
        assertEquals(
            "/data/local/tmp/Ab3xYz/server.log",
            NativeServicePaths.logPath(ExecutionIdentity.SHIZUKU_SHELL, prefix),
        )
    }

    @Test
    fun rootPathsIncludeRandomPrefix() {
        val prefix = "k9Lm2P"
        assertEquals(
            "/data/k9Lm2P/server",
            NativeServicePaths.serverRoot(ExecutionIdentity.ROOT, prefix),
        )
    }

    @Test
    fun shizukuRootPathsUseDataPrefix() {
        val prefix = "k9Lm2P"
        assertEquals(
            "/data/k9Lm2P/server",
            NativeServicePaths.serverRoot(ExecutionIdentity.SHIZUKU_ROOT, prefix),
        )
        assertEquals(
            "/data/k9Lm2P/usr/.local/properties",
            NativeServicePaths.configPath(ExecutionIdentity.SHIZUKU_ROOT, prefix),
        )
    }
}
