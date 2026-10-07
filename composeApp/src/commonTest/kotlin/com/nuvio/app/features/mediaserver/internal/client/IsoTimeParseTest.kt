package com.nuvio.app.features.mediaserver.internal.client

import com.nuvio.app.features.mediaserver.internal.client.mediabrowser.IsoTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IsoTimeParseTest {
    @Test
    fun parsesTheFormsTheServersWrite() {
        assertEquals(0L, IsoTime.parse("1970-01-01T00:00:00Z"))
        assertEquals(1_759_782_669_201L, IsoTime.parse("2025-10-06T20:31:09.2016699Z"), "a 7-digit .NET fraction keeps milliseconds")
        assertEquals(1_759_782_669_000L, IsoTime.parse("2025-10-06T20:31:09Z"))
        assertEquals(1_759_782_669_000L, IsoTime.parse("2025-10-06T22:31:09+02:00"), "an offset is applied")
        assertEquals(1_759_782_669_000L, IsoTime.parse("2025-10-06T20:31:09"), "no offset = UTC")
    }

    @Test
    fun isTheInverseOfFormat() {
        for (ms in listOf(0L, 86_399_000L, 951_782_400_000L, 1_759_782_669_000L, 4_102_444_799_000L)) {
            assertEquals(ms, IsoTime.parse(IsoTime.format(ms)), "round trip of $ms")
        }
    }

    @Test
    fun garbageIsNullNotACrash() {
        assertNull(IsoTime.parse(null))
        assertNull(IsoTime.parse(""))
        assertNull(IsoTime.parse("yesterday"))
        assertNull(IsoTime.parse("2025-13-40T25:61:61Z"))
    }
}
