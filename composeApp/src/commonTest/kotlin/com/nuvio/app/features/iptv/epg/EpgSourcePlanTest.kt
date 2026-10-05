package com.nuvio.app.features.iptv.epg

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * F14 — one playlist, several guides, in priority order. Red before lane G: an explicit EPG URL
 * REPLACED the provider's own guide and only the first `url-tvg` URL was read. Twin: NuvioTV
 * EpgSourcePlanTest (JUnit, by hand).
 */
class EpgSourcePlanTest {

    @Test
    fun typedUrlsComeFirstThenTheProviderGuide() {
        val plan = EpgSourcePlan.plan(
            explicit = "https://a.example/guide.xml\nhttps://b.example/epg.xml.gz",
            derivedXtream = "http://panel:8080/xmltv.php?username=u&password=p",
            urlTvg = null,
        )
        assertEquals(
            listOf(
                EpgSource("https://a.example/guide.xml", EpgSourceKind.EXPLICIT),
                EpgSource("https://b.example/epg.xml.gz", EpgSourceKind.EXPLICIT),
                EpgSource("http://panel:8080/xmltv.php?username=u&password=p", EpgSourceKind.XTREAM_DERIVED),
            ),
            plan,
        )
    }

    @Test
    fun everyUrlTvgUrlIsUsedAndDuplicatesCollapse() {
        val plan = EpgSourcePlan.plan(
            explicit = "http://x/1.xml",
            derivedXtream = null,
            urlTvg = "http://x/1.xml,http://x/2.xml, https://x/3.xml",
        )
        assertEquals(listOf("http://x/1.xml", "http://x/2.xml", "https://x/3.xml"), plan.map { it.url })
        assertEquals(EpgSourceKind.URL_TVG, plan[1].kind)
    }

    @Test
    fun aCommaInsideAQueryStringIsNotASeparator() {
        assertEquals(
            listOf("http://h/epg.php?ids=1,2,3&x=y", "http://h/other.xml"),
            EpgSourcePlan.splitUrls("http://h/epg.php?ids=1,2,3&x=y,http://h/other.xml"),
        )
    }

    @Test
    fun blankAndCappedInputs() {
        assertEquals(emptyList(), EpgSourcePlan.plan(explicit = "  ", derivedXtream = null, urlTvg = null))
        val many = (1..9).joinToString("\n") { "http://h/$it.xml" }
        assertEquals(EpgSourcePlan.MAX_SOURCES, EpgSourcePlan.plan(many, "http://panel/xmltv.php", null).size)
    }

    @Test
    fun laterSourcesAreNamespacedSoTheirRowsNeverInterleave() {
        assertEquals("bbc1.uk", EpgSourcePlan.storedKey(0, "bbc1.uk"))
        assertEquals("s2:bbc1.uk", EpgSourcePlan.storedKey(2, "bbc1.uk"))
    }
}
