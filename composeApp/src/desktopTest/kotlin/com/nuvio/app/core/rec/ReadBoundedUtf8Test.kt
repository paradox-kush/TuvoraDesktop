package com.nuvio.app.core.rec

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReadBoundedUtf8Test {
    @Test
    fun `reads content within the limit`() {
        val data = "hello world".encodeToByteArray()
        assertEquals("hello world", readBoundedUtf8(ByteArrayInputStream(data), maxBytes = 1024))
    }

    @Test
    fun `returns null when the stream exceeds the byte limit`() {
        val data = ByteArray(2048) { 'x'.code.toByte() }
        assertNull(
            readBoundedUtf8(ByteArrayInputStream(data), maxBytes = 1024),
            "over the byte cap, enforced while consuming",
        )
    }

    @Test
    fun `content exactly at the limit is kept`() {
        val data = ByteArray(1024) { 'a'.code.toByte() }
        assertEquals(1024, readBoundedUtf8(ByteArrayInputStream(data), maxBytes = 1024)?.length)
    }

    @Test
    fun `empty stream yields empty string`() {
        assertEquals("", readBoundedUtf8(ByteArrayInputStream(ByteArray(0)), maxBytes = 16))
    }
}
