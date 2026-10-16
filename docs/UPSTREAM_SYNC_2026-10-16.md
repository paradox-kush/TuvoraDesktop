# Upstream sync — 2026-10-16

This merge starts from Tuvora `org/main` and preserves the released fork history. Existing feature branches and uncommitted primary-checkout work are untouched.

## Integration decisions

- Keep switchable Tuvora backend identity, the single-flight Supabase client, and the single auth refresher. Port the upstream backend rate-limit coordinator and realtime reconnect socket through that provider.
- Preserve setup-code deep links alongside upstream party links, Tuvora branding, build workflows, credentials privacy, feature gates, and the architecture baseline.
- Watch Party remains disabled: neither the Tuvora backend nor the public `NuvioMedia/self-host` snapshot fetched on 2026-10-16 contains `watch_party_*` RPCs or migrations. No backend schema or dashboard/account changes are needed while that capability remains disabled. Enabling it requires a backend implementation and TV playback integration.
- Keep rejecting custom-poster patterns without placeholders. Upstream's token-cache optimization accidentally changed that existing contract; TV already rejects them and the KMP twins now agree.

## Source

`origin/Dev` at `ee0048d02f752a010050e72d474145dbe62b7b41`; 145 upstream commits absent from the pre-merge Tuvora main.

## Desktop adapters

Preserve native-player dependency injection, lazy bridge loading, Live TV fullscreen ownership, memory-tier image caching, and the IPTV search lane. Add upstream server search targets, native buffering/control changes, and explicit native-load error reporting without replacing those fork behaviors.

## Validation

- `:composeApp:compileKotlinDesktop` passed.
- Focused `:composeApp:desktopTest` across architecture, networking, posters, party, player, MDBList, and media servers passed: 929 tests, 5 skipped, 0 failures (924 passed). All five architecture checks passed and the architecture baseline is unchanged.
- An initial unfiltered desktop suite was stopped after it stalled in the unchanged `StalkerMinistraSeriesTest` cold-session case. The poster regression it found was corrected before the successful focused run.
- Resource XML duplicate checks, conflict-marker checks, and `git diff --check` passed.
