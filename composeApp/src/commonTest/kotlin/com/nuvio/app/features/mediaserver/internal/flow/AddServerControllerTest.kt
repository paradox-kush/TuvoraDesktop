package com.nuvio.app.features.mediaserver.internal.flow

import com.nuvio.app.features.mediaserver.api.MediaServerType
import com.nuvio.app.features.mediaserver.internal.FakeHttp
import com.nuvio.app.features.mediaserver.internal.M
import com.nuvio.app.features.mediaserver.internal.MediaServerAccounts
import com.nuvio.app.features.mediaserver.internal.TestRig
import com.nuvio.app.features.mediaserver.internal.U
import com.nuvio.app.features.mediaserver.internal.client.MediaServerException
import com.nuvio.app.features.mediaserver.internal.client.MediaServerRequest
import com.nuvio.app.features.mediaserver.internal.client.MediaServerResponse
import com.nuvio.app.features.mediaserver.internal.client.MediaServerTrust
import com.nuvio.app.features.mediaserver.internal.entry
import com.nuvio.app.features.mediaserver.internal.json
import com.nuvio.app.features.mediaserver.internal.store.MediaServerTrustStore
import com.nuvio.app.features.mediaserver.internal.store.StoredCredential
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AddServerControllerTest {
    private val publicInfo = """{"LocalAddress":"http://nas:8096","ServerName":"Living Room","Version":"12.2.0","ProductName":"Jellyfin Server","Id":"$M","StartupWizardCompleted":true}"""
    private val authOk = """{"User":{"Id":"$U","Name":"kid","ServerId":"$M"},"AccessToken":"TOKEN-9","ServerId":"$M"}"""

    private class Disk { var json: String? = null }
    private val disk = Disk()
    private val trust = MediaServerTrust(MediaServerTrustStore({ disk.json }, { disk.json = it }, { disk.json = null }))

    private fun path(r: MediaServerRequest) = r.url.substringAfter("//").substringAfter('/', "").substringBefore('?').let { "/$it" }

    /** A Jellyfin that answers the anonymous calls; [extra] adds/overrides routes. */
    private fun jellyfin(quickConnect: Boolean = true, extra: (MediaServerRequest) -> MediaServerResponse? = { null }) = FakeHttp { r ->
        extra(r) ?: when (path(r)) {
            "/System/Info/Public" -> json(publicInfo)
            "/QuickConnect/Enabled" -> json(quickConnect.toString())
            "/Users/Public" -> json("""[{"Id":"$U","Name":"kid"},{"Id":"2","Name":"mum"}]""")
            else -> json("", status = 404)
        }
    }

    private fun TestScope.controller(rig: TestRig, existing: com.nuvio.app.features.mediaserver.api.MediaServerEntry? = null, signedIn: MutableList<String> = mutableListOf()): AddServerController =
        AddServerController(rig.services, MediaServerAccounts(rig.store, rig.services, 1..2), trust, this, existing) { signedIn += it.key }

    private fun rig(http: FakeHttp) = TestRig(http = http).also { it.store.ensureLoaded() }

    @Test
    fun anEmptyAddressIsRefusedWithoutAnyRequest() = runTest {
        val rig = rig(jellyfin())
        val c = controller(rig)
        c.connect()
        assertEquals(AddError.INVALID_ADDRESS, c.state.value.error)
        assertEquals(AddStage.ADDRESS, c.state.value.stage)
        assertTrue(rig.http.requests.isEmpty())
    }

    @Test
    fun aReachableServerMovesToTheSignInChoiceWithQuickConnectAndTheUserPicker() = runTest {
        val rig = rig(jellyfin())
        val c = controller(rig)
        c.setAddress("nas:8096")
        c.connect()
        advanceUntilIdle()
        val s = c.state.value
        assertEquals(AddStage.CHOOSE_SIGN_IN, s.stage)
        assertEquals("Living Room", s.found?.info?.name)
        assertTrue(s.quickConnectAvailable)
        assertEquals(listOf("kid", "mum"), s.publicUsers.map { it.name })
        assertFalse(s.busy)
    }

    @Test
    fun aServerWithoutQuickConnectOffersOnlyThePassword() = runTest {
        val rig = rig(jellyfin(quickConnect = false))
        val c = controller(rig)
        c.setAddress("nas:8096"); c.connect(); advanceUntilIdle()
        assertFalse(c.state.value.quickConnectAvailable)
        c.startQuickConnect()
        assertEquals(AddStage.CHOOSE_SIGN_IN, c.state.value.stage, "no code screen for a server that cannot do it")
    }

    @Test
    fun theProductThePersonPickedIsCorrectedToWhatTheServerSaysItIs() = runTest {
        val rig = rig(jellyfin())
        val c = controller(rig)
        c.selectType(MediaServerType.EMBY)
        c.setAddress("nas:8096"); c.connect(); advanceUntilIdle()
        assertEquals(MediaServerType.JELLYFIN, c.state.value.selectedType)
        assertEquals(MediaServerType.EMBY, c.state.value.typeCorrectedFrom, "said once, so the person knows why Emby did not stick")
    }

    @Test
    fun nothingListeningIsUnreachableAndAPageThatIsNotAServerSaysSo() = runTest {
        val down = rig(FakeHttp { throw MediaServerException.Unreachable("refused") })
        val c1 = controller(down)
        c1.setAddress("nas.example.com"); c1.connect(); advanceUntilIdle()
        assertEquals(AddError.UNREACHABLE, c1.state.value.error)
        assertEquals(AddStage.ADDRESS, c1.state.value.stage)

        val proxy = rig(FakeHttp { json("<html>login</html>") })
        val c2 = controller(proxy)
        c2.setAddress("http://nas:8096"); c2.connect(); advanceUntilIdle()
        assertEquals(AddError.NOT_A_MEDIA_SERVER, c2.state.value.error)
    }

    @Test
    fun anUntrustedCertificateShowsItsFingerprintAndTrustingItPinsAndRetries() = runTest {
        var trusted = false
        val rig = rig(FakeHttp { r ->
            if (path(r) == "/System/Info/Public" && !trusted) throw MediaServerException.CertificateUntrusted("nas:8920", "SELF_SIGNED", "AB:12:CD")
            else if (path(r) == "/System/Info/Public") json(publicInfo)
            else if (path(r) == "/QuickConnect/Enabled") json("false")
            else json("[]")
        })
        val c = controller(rig)
        c.setAddress("https://nas:8920"); c.connect(); advanceUntilIdle()
        val prompt = assertNotNull(c.state.value.certPrompt)
        assertEquals("AB:12:CD", prompt.fingerprint)
        assertEquals(AddStage.ADDRESS, c.state.value.stage)

        trusted = true // the platform glue would now accept the pinned certificate
        c.trustCertificate(); advanceUntilIdle()
        assertEquals("AB:12:CD", trust.pinnedFingerprint("nas:8920"), "the exact fingerprint shown is the one pinned")
        assertEquals(AddStage.CHOOSE_SIGN_IN, c.state.value.stage)
        assertNull(c.state.value.certPrompt)
    }

    @Test
    fun decliningTheCertificateKeepsNothingPinned() = runTest {
        val rig = rig(FakeHttp { throw MediaServerException.CertificateUntrusted("nas:8920", "SELF_SIGNED", "AB:12") })
        val c = controller(rig)
        c.setAddress("https://nas:8920"); c.connect(); advanceUntilIdle()
        c.declineCertificate()
        assertNull(c.state.value.certPrompt)
        assertNull(trust.pinnedFingerprint("nas:8920"))
    }

    @Test
    fun passwordSignInAddsTheServerStoresOnlyTheTokenAndNeverTheTypedPassword() = runTest {
        var authBody: String? = null
        val rig = rig(jellyfin { r -> if (path(r) == "/Users/AuthenticateByName") { authBody = r.body; json(authOk) } else null })
        val signedIn = mutableListOf<String>()
        val c = controller(rig, signedIn = signedIn)
        c.setAddress("nas:8096"); c.connect(); advanceUntilIdle()
        c.usePassword()
        c.submitPassword("kid", "hunter2")
        advanceUntilIdle()
        assertEquals(AddStage.OFFER_HOME_ROW, c.state.value.stage, "a signed-in server asks about Recently added before finishing")
        assertEquals("jellyfin|$M|$U", c.state.value.signedIn?.key)
        assertEquals("TOKEN-9", rig.credentials.token("jellyfin:$M:$U"))
        assertEquals(listOf("jellyfin|$M|$U"), signedIn)
        assertTrue(authBody!!.contains("hunter2"), "the password went to the server once")
        assertTrue(rig.secure.items.values.none { "hunter2" in it } && rig.persistence.blobs.values.none { "hunter2" in it }, "...and nowhere on disk")
        assertFalse(c.state.value.toString().contains("hunter2"), "...nor in the flow's own state")
    }

    @Test
    fun aWrongPasswordStaysOnThePasswordStepWithAClearError() = runTest {
        val rig = rig(jellyfin { r -> if (path(r) == "/Users/AuthenticateByName") json("", status = 401) else null })
        val c = controller(rig)
        c.setAddress("nas:8096"); c.connect(); advanceUntilIdle()
        c.usePassword(); c.submitPassword("kid", "nope"); advanceUntilIdle()
        assertEquals(AddStage.PASSWORD, c.state.value.stage)
        assertEquals(AddError.WRONG_CREDENTIALS, c.state.value.error)
        assertFalse(c.state.value.busy)
        assertTrue(rig.store.current().isEmpty())
    }

    @Test
    fun quickConnectShowsTheCodeThenSignsInOnceApproved() = runTest {
        var polls = 0
        val rig = rig(jellyfin { r ->
            when (path(r)) {
                "/QuickConnect/Initiate" -> json("""{"Code":"123456","Secret":"SEC-1","Authenticated":false}""")
                "/QuickConnect/Connect" -> json("""{"Code":"123456","Secret":"SEC-1","Authenticated":${++polls >= 2}}""")
                "/Users/AuthenticateWithQuickConnect" -> json(authOk)
                else -> null
            }
        })
        val c = controller(rig)
        c.setAddress("nas:8096"); c.connect(); advanceUntilIdle()
        c.startQuickConnect()
        assertEquals(AddStage.QUICK_CONNECT, c.state.value.stage)
        this.launch { c.runQuickConnect() }
        advanceUntilIdle()
        assertEquals(AddStage.OFFER_HOME_ROW, c.state.value.stage, "a signed-in server asks about Recently added before finishing")
        assertEquals("TOKEN-9", rig.credentials.token("jellyfin:$M:$U"))
    }

    @Test
    fun theCodeIsShownGroupedWhileWaiting() = runTest {
        val rig = rig(jellyfin { r ->
            when (path(r)) {
                "/QuickConnect/Initiate" -> json("""{"Code":"654321","Secret":"SEC-1","Authenticated":false}""")
                "/QuickConnect/Connect" -> json("""{"Code":"654321","Secret":"SEC-1","Authenticated":false}""")
                else -> null
            }
        })
        val c = controller(rig)
        c.setAddress("nas:8096"); c.connect(); advanceUntilIdle()
        c.startQuickConnect()
        val job = this.launch { c.runQuickConnect() }
        testScheduler.runCurrent()
        assertEquals("654 321", c.state.value.quickConnect?.displayCode)
        job.cancel()
        assertEquals(AddStage.QUICK_CONNECT, c.state.value.stage, "cancelling (screen left) is not a failure")
    }

    @Test
    fun aLapsedCodeIsRegeneratedWithoutTheScreenDoingAnything() = runTest {
        var initiates = 0
        val rig = rig(jellyfin { r ->
            when (path(r)) {
                "/QuickConnect/Initiate" -> json("""{"Code":"11111${++initiates}","Secret":"SEC-$initiates","Authenticated":false}""")
                "/QuickConnect/Connect" -> if (r.url.contains("SEC-1")) json("", status = 404) else json("""{"Code":"111112","Secret":"SEC-2","Authenticated":true}""")
                "/Users/AuthenticateWithQuickConnect" -> json(authOk)
                else -> null
            }
        })
        val c = controller(rig)
        c.setAddress("nas:8096"); c.connect(); advanceUntilIdle()
        c.startQuickConnect()
        this.launch { c.runQuickConnect() }
        advanceUntilIdle()
        assertEquals(2, initiates)
        assertEquals(AddStage.OFFER_HOME_ROW, c.state.value.stage, "a signed-in server asks about Recently added before finishing")
    }

    @Test
    fun signingAnExistingEntryInKeepsItsProductAndRefusesADifferentServer() = runTest {
        val existing = entry(address = "http://nas:8096")
        val rig = rig(FakeHttp { r ->
            when (path(r)) {
                "/System/Info/Public" -> json(publicInfo.replace(M, "ffffffffffffffffffffffffffffffff")) // the address now answers as ANOTHER server
                "/QuickConnect/Enabled" -> json("false")
                "/Users/Public" -> json("[]")
                "/Users/AuthenticateByName" -> json(authOk)
                else -> json("", status = 404)
            }
        })
        rig.store.applyFromRemote(1, listOf(existing))
        val c = controller(rig, existing)
        assertEquals("http://nas:8096", c.state.value.address, "prefilled from the synced address")
        c.selectType(MediaServerType.EMBY)
        assertEquals(MediaServerType.JELLYFIN, c.state.value.selectedType, "the product of an existing entry is fixed")
        c.connect(); advanceUntilIdle()
        c.usePassword(); c.submitPassword("kid", "pw"); advanceUntilIdle()
        assertEquals(AddError.DIFFERENT_SERVER, c.state.value.error, "never silently re-point a signed-in session")
        assertNull(rig.credentials.token(existing.serverKey), "no token saved for a server that is not this entry")
    }

    @Test
    fun signingAnExistingEntryInOnThisDeviceStoresItsToken() = runTest {
        val existing = entry(address = null)
        val rig = rig(jellyfin { r -> if (path(r) == "/Users/AuthenticateByName") json(authOk) else null })
        rig.store.applyFromRemote(1, listOf(existing))
        val c = controller(rig, existing)
        c.setAddress("nas:8096"); c.connect(); advanceUntilIdle()
        c.usePassword(); c.submitPassword("kid", "pw"); advanceUntilIdle()
        assertEquals(AddStage.OFFER_HOME_ROW, c.state.value.stage, "a signed-in server asks about Recently added before finishing")
        assertEquals("TOKEN-9", rig.credentials.token(existing.serverKey))
        assertEquals(1, rig.store.current().size, "the synced entry was signed in, not duplicated")
        assertEquals("http://nas:8096", rig.store.current().single().address, "and now carries the address that was verified")
    }

    @Test
    fun theServerNameDefaultsToTheServersOwnNameNotTheHostAndCanBeEdited() = runTest {
        val rig = rig(jellyfin { r -> if (path(r) == "/Users/AuthenticateByName") json(authOk) else null })
        val c = controller(rig)
        c.setAddress("192.168.1.20:8096"); c.connect(); advanceUntilIdle()
        assertEquals("Living Room", c.state.value.serverName, "the server's own ServerName, not 192.168.1.20")
        c.setServerName("Basement NAS")
        c.usePassword(); c.submitPassword("kid", "pw"); advanceUntilIdle()
        assertEquals("Basement NAS", c.state.value.signedIn?.name)
        assertEquals("Basement NAS", rig.store.current().single().name)
    }

    @Test
    fun aBlankServerNameFallsBackToTheServersOwnName() = runTest {
        val rig = rig(jellyfin { r -> if (path(r) == "/Users/AuthenticateByName") json(authOk) else null })
        val c = controller(rig)
        c.setAddress("nas:8096"); c.connect(); advanceUntilIdle()
        c.setServerName("   ")
        c.usePassword(); c.submitPassword("kid", "pw"); advanceUntilIdle()
        assertEquals("Living Room", c.state.value.signedIn?.name)
    }

    @Test
    fun oneTapTurnsRecentlyAddedOnAndLeavesTheOtherRowsOff() = runTest {
        val rig = rig(jellyfin { r -> if (path(r) == "/Users/AuthenticateByName") json(authOk) else null })
        val signedIn = mutableListOf<String>()
        val c = controller(rig, signedIn = signedIn)
        c.setAddress("nas:8096"); c.connect(); advanceUntilIdle()
        c.usePassword(); c.submitPassword("kid", "pw"); advanceUntilIdle()
        assertTrue(rig.store.current().single().homeRows.isEmpty(), "default off until the person says yes")
        c.enableRecentlyAdded()
        assertEquals(AddStage.DONE, c.state.value.stage)
        assertEquals(setOf(com.nuvio.app.features.mediaserver.api.MediaServerHomeRow.RECENTLY_ADDED), rig.store.current().single().homeRows)
        assertEquals(setOf(com.nuvio.app.features.mediaserver.api.MediaServerHomeRow.RECENTLY_ADDED), c.state.value.signedIn?.homeRows)
        assertEquals(2, signedIn.size, "Home is told again so the new row shows")
    }

    @Test
    fun skippingTheOfferLeavesEveryServerRowOff() = runTest {
        val rig = rig(jellyfin { r -> if (path(r) == "/Users/AuthenticateByName") json(authOk) else null })
        val c = controller(rig)
        c.setAddress("nas:8096"); c.connect(); advanceUntilIdle()
        c.usePassword(); c.submitPassword("kid", "pw"); advanceUntilIdle()
        c.skipHomeRowOffer()
        assertEquals(AddStage.DONE, c.state.value.stage)
        assertTrue(rig.store.current().single().homeRows.isEmpty())
    }

    @Test
    fun anEntryThatAlreadyShowsRecentlyAddedIsNotAskedAgain() = runTest {
        val existing = entry(address = null).copy(homeRows = setOf(com.nuvio.app.features.mediaserver.api.MediaServerHomeRow.RECENTLY_ADDED))
        val rig = rig(jellyfin { r -> if (path(r) == "/Users/AuthenticateByName") json(authOk) else null })
        rig.store.applyFromRemote(1, listOf(existing))
        val c = controller(rig, existing)
        c.setAddress("nas:8096"); c.connect(); advanceUntilIdle()
        c.usePassword(); c.submitPassword("kid", "pw"); advanceUntilIdle()
        assertEquals(AddStage.DONE, c.state.value.stage)
    }

    @Test
    fun signingAnExistingEntryInNeverRenamesIt() = runTest {
        val existing = entry(address = null)
        val rig = rig(jellyfin { r -> if (path(r) == "/Users/AuthenticateByName") json(authOk) else null })
        rig.store.applyFromRemote(1, listOf(existing))
        val c = controller(rig, existing)
        c.setAddress("nas:8096"); c.connect(); advanceUntilIdle()
        c.setServerName("Something else")
        c.usePassword(); c.submitPassword("kid", "pw"); advanceUntilIdle()
        assertEquals(existing.name, rig.store.current().single().name)
    }
}
