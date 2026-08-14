package com.nuvio.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileDescriptorDiagnosticsTest {
    @Test
    fun classifiesNativePlaybackDescriptorTypesWithoutRetainingTargets() {
        assertEquals(FileDescriptorKind.SOCKET, classifyFileDescriptorTarget("socket:[123]"))
        assertEquals(FileDescriptorKind.PIPE, classifyFileDescriptorTarget("pipe:[456]"))
        assertEquals(FileDescriptorKind.EVENT_FD, classifyFileDescriptorTarget("anon_inode:[eventfd]"))
        assertEquals(FileDescriptorKind.FENCE, classifyFileDescriptorTarget("anon_inode:[sync_file]"))
        assertEquals(FileDescriptorKind.DMA_BUFFER, classifyFileDescriptorTarget("anon_inode:[dmabuf]"))
        assertEquals(FileDescriptorKind.DEVICE, classifyFileDescriptorTarget("/dev/kgsl-3d0"))
        assertEquals(FileDescriptorKind.FILE, classifyFileDescriptorTarget("/data/user/0/app/cache/file"))
        assertEquals(FileDescriptorKind.OTHER, classifyFileDescriptorTarget(null))
    }

    @Test
    fun parsesAndroidOpenFileSoftLimit() {
        val limits = """
            Limit                     Soft Limit           Hard Limit           Units
            Max cpu time              unlimited            unlimited            seconds
            Max open files            32768                32768                files
        """.trimIndent()

        assertEquals(32_768L, parseOpenFileSoftLimit(limits))
        assertNull(parseOpenFileSoftLimit("Max processes 123 456 processes"))
    }

    @Test
    fun lifecycleHistoryStaysBoundedAndKeepsNewestEntries() {
        var history: String? = null
        repeat(20) { index ->
            history = appendMpvLifecycleHistory(history, index.toLong(), 4L, "stage_$index", 1, maxChars = 80)
        }

        val value = requireNotNull(history)
        assertEquals(true, value.length <= 80)
        assertEquals(true, value.contains("stage_19"))
        assertEquals(false, value.contains("stage_0,"))
    }
}
