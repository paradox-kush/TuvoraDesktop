package com.nuvio.app.features.player

import android.view.SurfaceHolder
import kotlin.test.Test
import kotlin.test.assertEquals

class MpvSurfaceThreadingTest {
    @Test
    fun surfaceResizeIsOverriddenSoNativeWriteNeverRunsOnMain() {
        val viewClass = Class.forName("com.nuvio.app.features.player.NuvioLibmpvView")
        val method = viewClass.getDeclaredMethod(
            "surfaceChanged",
            SurfaceHolder::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )

        assertEquals(
            viewClass,
            method.declaringClass,
            "surfaceChanged must stay overridden: BaseMPVView calls mpv synchronously on Main.",
        )
    }

    @Test
    fun surfaceAttachAndDetachAreOverriddenSoLifecycleCallsNeverRunOnMain() {
        val viewClass = Class.forName("com.nuvio.app.features.player.NuvioLibmpvView")

        listOf("surfaceCreated", "surfaceDestroyed").forEach { methodName ->
            val declaringClass = viewClass.getDeclaredMethod(
                methodName,
                SurfaceHolder::class.java,
            ).declaringClass

            assertEquals(
                viewClass,
                declaringClass,
                "$methodName must stay overridden: BaseMPVView performs synchronous native " +
                    "surface lifecycle calls on Main instead of the serialized mpv control queue.",
            )
        }
    }
}
