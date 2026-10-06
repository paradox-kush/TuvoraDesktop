package com.nuvio.app.features.mediaserver.internal.flow

import com.nuvio.app.features.mediaserver.api.MediaServerType
import com.nuvio.app.features.mediaserver.internal.FakeClient
import com.nuvio.app.features.mediaserver.internal.M
import com.nuvio.app.features.mediaserver.internal.TestRig
import com.nuvio.app.features.mediaserver.internal.client.HealthStatus
import com.nuvio.app.features.mediaserver.internal.client.MediaServerException
import com.nuvio.app.features.mediaserver.internal.entry
import com.nuvio.app.features.mediaserver.internal.store.StoredCredential
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ApproveCodeControllerTest {
    private val fake = FakeClient()
    private val jf = entry()
    private val emby = entry(type = MediaServerType.EMBY, machineId = "e".repeat(32), name = "Emby box")

    private fun rig() = TestRig(clientFactory = { fake }).also { r ->
        r.store.applyFromRemote(1, listOf(jf, emby))
        r.credentials.save(jf.serverKey, StoredCredential("T1"))
        r.credentials.save(emby.serverKey, StoredCredential("T2"))
    }

    @Test
    fun onlySignedInJellyfinServersCanApproveACode() = runTest {
        val rig = rig()
        val c = ApproveCodeController(rig.services, { rig.store.current() }, this)
        assertEquals(listOf(jf.key), c.state.value.servers.map { it.key }, "Emby has no Quick Connect")
        assertEquals(jf.key, c.state.value.selectedKey, "a single choice is preselected")
    }

    @Test
    fun aTypedCodeWithSpacesIsNormalisedAndApprovedWithThisDevicesSession() = runTest {
        val rig = rig()
        val c = ApproveCodeController(rig.services, { rig.store.current() }, this)
        c.setCode("123 456")
        c.approve(); advanceUntilIdle()
        assertEquals(ApproveStatus.APPROVED, c.state.value.status)
        assertEquals(listOf("123456"), fake.authorized)
        assertEquals("", c.state.value.code, "cleared so the next device starts clean")
    }

    @Test
    fun aCodeThatIsNotSixDigitsNeverReachesTheServer() = runTest {
        val rig = rig()
        val c = ApproveCodeController(rig.services, { rig.store.current() }, this)
        c.setCode("12345"); c.approve(); advanceUntilIdle()
        assertEquals(ApproveStatus.INVALID_CODE, c.state.value.status)
        c.setCode("12a456"); c.approve(); advanceUntilIdle()
        assertEquals(ApproveStatus.INVALID_CODE, c.state.value.status)
        assertTrue(fake.authorized.isEmpty())
    }

    @Test
    fun theServersAnswerBecomesAHumanStatus() {
        assertEquals(ApproveStatus.CODE_NOT_FOUND, ApproveCodeController.statusFor(MediaServerException.Http(404)))
        assertEquals(ApproveStatus.CODE_NOT_FOUND, ApproveCodeController.statusFor(MediaServerException.Http(400)))
        assertEquals(ApproveStatus.SIGN_IN_AGAIN, ApproveCodeController.statusFor(MediaServerException.Http(401)))
        assertEquals(ApproveStatus.SIGN_IN_AGAIN, ApproveCodeController.statusFor(MediaServerException.Http(403)))
        assertEquals(ApproveStatus.UNREACHABLE, ApproveCodeController.statusFor(MediaServerException.Http(503)))
        assertEquals(ApproveStatus.UNREACHABLE, ApproveCodeController.statusFor(MediaServerException.Unreachable("x")))
    }

    @Test
    fun aRevokedApprovingSessionDropsToSignInAgain() = runTest {
        val rig = rig()
        fake.failWith = MediaServerException.Http(401)
        val c = ApproveCodeController(rig.services, { rig.store.current() }, this)
        c.setCode("123456"); c.approve(); advanceUntilIdle()
        assertEquals(ApproveStatus.SIGN_IN_AGAIN, c.state.value.status)
        assertTrue(jf.serverKey in rig.services.expiredSessions.value)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MediaServerListControllerTest {
    private val fake = FakeClient()

    @Test
    fun aHealthCheckIsAskedOncePerVisitAndNotDuplicatedWhileRunning() = runTest {
        var calls = 0
        val rig = TestRig(clientFactory = { object : com.nuvio.app.features.mediaserver.internal.client.MediaServerClient by fake {
            override suspend fun me(): com.nuvio.app.features.mediaserver.internal.client.mediabrowser.UserDto { calls++; return fake.me() }
        } })
        val e = entry()
        rig.store.applyFromRemote(1, listOf(e))
        rig.credentials.save(e.serverKey, StoredCredential("T"))
        val c = MediaServerListController(rig.services, this)
        c.checkOnce(listOf(e)); c.checkOnce(listOf(e))
        advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals(HealthStatus.ONLINE, c.healthState.value[e.serverKey])
        assertTrue(c.checkingState.value.isEmpty())
        val rows = c.rows(listOf(e), rig.services.expiredSessions.value, c.healthState.value, c.checkingState.value)
        assertEquals(ServerStatus.SIGNED_IN, rows.single().status)
    }

    @Test
    fun anEntryThatIsNotSignedInIsNeverAskedAnything() = runTest {
        val rig = TestRig(http = com.nuvio.app.features.mediaserver.internal.FakeHttp { error("no request expected") })
        val e = entry()
        rig.store.applyFromRemote(1, listOf(e))
        val c = MediaServerListController(rig.services, this)
        c.checkOnce(listOf(e)); advanceUntilIdle()
        assertTrue(c.healthState.value.isEmpty())
        assertEquals(ServerStatus.NEEDS_SIGN_IN, c.rows(listOf(e), emptySet(), emptyMap(), emptySet()).single().status)
    }
}
