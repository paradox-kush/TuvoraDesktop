package com.nuvio.app.features.recordings

import com.nuvio.app.features.recordings.internal.DesktopRecordingFiles
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class DesktopRecordingFilesTest {
    private val root = Files.createTempDirectory("rec-files").toFile()
    private var recDir = File(root, "rec")
    private val files = DesktopRecordingFiles({ File(root, "state") }) { recDir }

    @AfterTest
    fun tearDown() { root.deleteRecursively() }

    @Test
    fun `a write replaces the old text whole and leaves no temporary file`() = runBlocking {
        assertNull(files.read("a.json"))
        files.write("a.json", "one")
        files.write("a.json", "two")
        assertEquals("two", files.read("a.json"))
        assertTrue(File(root, "state").list()!!.none { it.endsWith(".tmp") })
    }

    @Test
    fun `keepAside moves the file and never deletes it`() = runBlocking {
        files.write("a.json", "broken")
        files.keepAside("a.json")
        assertNull(files.read("a.json"))
        val kept = File(root, "state").listFiles()!!.single()
        assertTrue(kept.name.startsWith("a.json.corrupt-"))
        assertEquals("broken", kept.readText())
    }

    @Test
    fun `only plain file names are accepted for recordings`() {
        assertEquals(File(root, "rec/show.ts").path, files.pathFor("show.ts"))
        assertTrue(runCatching { files.pathFor("../x") }.isFailure)
        assertTrue(runCatching { files.pathFor("") }.isFailure)
    }

    @Test
    fun `the recordings folder is read on every use so a later setting takes effect`() {
        val first = files.pathFor("show.ts")
        recDir = File(root, "chosen")
        assertEquals(File(root, "chosen/show.ts").path, files.pathFor("show.ts"))
        assertTrue(first != files.pathFor("show.ts"))
    }

    @Test
    fun `free bytes come from the folder and creating it is fine`() {
        assertTrue(files.freeBytes() > 0)
        assertTrue(recDir.isDirectory)
    }

    @Test
    fun `sizeOf and delete handle missing files`() = runBlocking {
        val path = files.pathFor("show.ts")
        assertEquals(0L, files.sizeOf(path))
        File(path).also { it.parentFile.mkdirs() }.writeText("12345")
        assertEquals(5L, files.sizeOf(path))
        files.delete(path)
        files.delete(path) // already gone: no error
        assertEquals(0L, files.sizeOf(path))
    }
}
