package com.codex.remote.data.ssh

import com.codex.remote.domain.RemotePlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshConfigTest {
    @Test
    fun excludesCurve25519ThatAndroidCannotInstantiate() {
        val names = androidCompatibleSshConfig().keyExchangeFactories.map { it.name }

        assertFalse(names.any { it.contains("curve25519", ignoreCase = true) })
        assertTrue(names.any { it.contains("ecdh", ignoreCase = true) || it.contains("group14", ignoreCase = true) })
    }

    @Test
    fun posixCommandsUseTheRemoteLoginShell() {
        assertEquals(
            "exec \"\${SHELL:-/bin/sh}\" -lc 'codex --version'",
            codexVersionCommand(RemotePlatform.POSIX),
        )
        val command=appServerCommand(RemotePlatform.POSIX)
        assertTrue(command.contains("-lc 'if codex app-server proxy --help"))
        assertTrue(command.contains("daemon start >&2 && { echo CODEB_WS_V1; exec codex app-server proxy"))
        assertFalse(command.contains("daemon restart"))
    }

    @Test
    fun windowsCommandAllowsTheRemotePowerShellProfile() {
        val command = appServerCommand(RemotePlatform.WINDOWS)

        assertTrue(command.contains("powershell.exe"))
        assertFalse(command.contains("-NoProfile"))
        assertTrue(command.contains("codex app-server --listen stdio://"))
    }
}
