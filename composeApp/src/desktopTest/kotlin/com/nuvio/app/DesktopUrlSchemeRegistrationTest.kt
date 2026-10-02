package com.nuvio.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopUrlSchemeRegistrationTest {
    @Test
    fun `windows registers the scheme per user with the launcher and the url as argument`() {
        val cmds = DesktopUrlSchemeRegistration.windowsRegCommands("""C:\Program Files\Tuvora\Tuvora.exe""")
        assertEquals(3, cmds.size)
        assertTrue(cmds.all { it[0] == "reg" && it[1] == "add" && it.last() == "/f" })
        assertTrue(cmds.all { it[2].startsWith("HKCU\\Software\\Classes\\tuvora") }, "per user: never needs admin rights")
        assertTrue(cmds.any { "URL Protocol" in it })
        assertTrue(cmds.last().contains("\"C:\\Program Files\\Tuvora\\Tuvora.exe\" \"%1\""))
    }

    @Test
    fun `linux entry advertises the scheme handler and passes the url`() {
        val entry = DesktopUrlSchemeRegistration.linuxDesktopEntry("/opt/tuvora/bin/Tuvora")
        assertTrue("Exec=\"/opt/tuvora/bin/Tuvora\" %u" in entry)
        assertTrue("MimeType=x-scheme-handler/tuvora;" in entry)
        assertTrue(entry.startsWith("[Desktop Entry]"))
    }

    @Test
    fun `a development run is not registered, an installed launcher is`() {
        assertFalse(DesktopUrlSchemeRegistration.isInstalledLauncher("/opt/homebrew/opt/openjdk@17/bin/java"))
        assertFalse(DesktopUrlSchemeRegistration.isInstalledLauncher("C:\\Java\\bin\\java.exe"))
        assertFalse(DesktopUrlSchemeRegistration.isInstalledLauncher("C:\\Java\\bin\\javaw.exe"))
        assertFalse(DesktopUrlSchemeRegistration.isInstalledLauncher(null))
        assertTrue(DesktopUrlSchemeRegistration.isInstalledLauncher("/opt/tuvora/bin/Tuvora"))
        assertTrue(DesktopUrlSchemeRegistration.isInstalledLauncher("C:\\Program Files\\Tuvora\\Tuvora.exe"))
    }
}
