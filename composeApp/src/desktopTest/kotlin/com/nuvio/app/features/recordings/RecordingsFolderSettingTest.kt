package com.nuvio.app.features.recordings

import com.nuvio.app.features.recordings.api.FolderChangeResult
import com.nuvio.app.features.recordings.internal.DesktopRecordingFiles
import com.nuvio.app.features.recordings.internal.RecordingsFolderPolicy
import com.nuvio.app.features.recordings.internal.RecordingsFolderSetting
import com.nuvio.app.features.recordings.internal.RecordingsFolderStore
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class RecordingsFolderSettingTest {
    private val root = Files.createTempDirectory("rec-folder").toFile()

    @AfterTest
    fun tearDown() {
        root.walkBottomUp().forEach { it.setWritable(true) }
        root.deleteRecursively()
    }

    @Test
    fun `an existing writable folder is accepted and leaves no probe file behind`() {
        assertTrue(RecordingsFolderPolicy.isWritable(root))
        assertTrue(root.list()!!.isEmpty(), "the check must not litter: ${root.list()?.toList()}")
    }

    @Test
    fun `a folder that does not exist yet is created when its parent is writable`() {
        val dir = File(root, "a/b")
        assertTrue(RecordingsFolderPolicy.isWritable(dir))
        assertTrue(dir.isDirectory)
    }

    @Test
    fun `a plain file is not a folder`() {
        val f = File(root, "f.txt").apply { writeText("x") }
        assertFalse(RecordingsFolderPolicy.isWritable(f))
    }

    @Test
    fun `a read-only folder is refused`() {
        val dir = File(root, "ro").apply { mkdirs() }
        dir.setWritable(false)
        if (dir.canWrite()) return // running as a user that ignores permission bits (root): nothing to prove
        assertFalse(RecordingsFolderPolicy.isWritable(dir))
    }

    @Test
    fun `a folder that cannot be created under a read-only parent is refused`() {
        val parent = File(root, "ro").apply { mkdirs() }
        parent.setWritable(false)
        if (parent.canWrite()) return
        assertFalse(RecordingsFolderPolicy.isWritable(File(parent, "child")))
    }

    private class MemoryStore : RecordingsFolderStore {
        var chosenPath: String? = null
        var earlierPaths: List<String> = emptyList()
        override fun chosen() = chosenPath
        override fun setChosen(path: String?) { chosenPath = path }
        override fun earlier() = earlierPaths
        override fun setEarlier(paths: List<String>) { earlierPaths = paths }
    }

    private val default get() = File(root, "default").apply { mkdirs() }

    private fun setting(store: MemoryStore, pick: File?, applied: MutableList<File?> = mutableListOf()) =
        RecordingsFolderSetting(store = store, defaultDir = default, pick = { pick }, onApplied = { applied += it })

    @Test
    fun `a folder that cannot be written is refused and nothing is remembered`() = runBlocking {
        val store = MemoryStore()
        val applied = mutableListOf<File?>()
        val bad = File(root, "plain").apply { writeText("x") }
        val result = setting(store, bad, applied).chooseNewFolder()
        assertEquals(FolderChangeResult.NotWritable(bad.path), result)
        assertNull(store.chosenPath)
        assertTrue(applied.isEmpty())
    }

    @Test
    fun `a cancelled chooser changes nothing`() = runBlocking {
        val store = MemoryStore()
        assertEquals(FolderChangeResult.Cancelled, setting(store, null).chooseNewFolder())
        assertNull(store.chosenPath)
    }

    @Test
    fun `a new folder is remembered applied and the old one is kept for existing recordings`() = runBlocking {
        val store = MemoryStore()
        val applied = mutableListOf<File?>()
        val s = setting(store, File(root, "new"), applied)
        assertEquals(FolderChangeResult.Changed(File(root, "new").path), s.chooseNewFolder())
        assertEquals(File(root, "new").path, store.chosenPath)
        assertEquals(listOf(default.path), store.earlierPaths)
        assertEquals(listOf<File?>(File(root, "new")), applied)
        assertEquals(File(root, "new").path, s.folderPath.value)
    }

    @Test
    fun `a remembered folder that is gone falls back to the default`() {
        val store = MemoryStore().apply { chosenPath = File(root, "gone").path }
        val s = setting(store, null)
        s.restore()
        assertEquals(default.path, s.folderPath.value)
    }

    @Test
    fun `an existing recording is still found in its old folder after the folder changes`() {
        val old = File(root, "old").apply { mkdirs() }
        File(old, "show-1.ts").writeText("data")
        val newDir = File(root, "new").apply { mkdirs() }
        val files = DesktopRecordingFiles(stateDirOf = { File(root, "state") }, earlierDirs = { listOf(old) }, recordingsDir = { newDir })
        assertEquals(File(old, "show-1.ts").path, files.pathFor("show-1.ts"))
        assertEquals(File(newDir, "fresh-2.ts").path, files.pathFor("fresh-2.ts"))
    }
}
