package com.nuvio.app.core.contracts

/**
 * Test-only: puts every plural source registry back to empty, so a test that wires sources (the
 * golden-list contract wires IPTV through the production registration) leaves the process as found.
 */
internal fun resetAllSourceRegistriesForTest() {
    StreamSourceRegistry.resetForTest()
    MetaSourceRegistry.resetForTest()
    SearchProviderRegistry.resetForTest()
    ContentClassifierRegistry.resetForTest()
    OwnSourcePolicy.resetForTest()
    HomeSectionContributorRegistry.resetForTest()
    PlaybackSessionReporterRegistry.resetForTest()
}
