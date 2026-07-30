<div align="center">

  <img src="composeApp/src/commonMain/composeResources/drawable/app_logo_wordmark.png" alt="Tuvora" width="300" />
  <br />
  <br />

  <p>
    Tuvora Desktop — a media app for Windows, macOS, and Linux.
    <br />
    Browse, organize, and play media from sources you add — with IPTV, Live TV, EPG, and Sports built in.
  </p>

</div>

## ⚠️ Alpha Software — Testers Only

Tuvora Desktop is currently in alpha. It is under active development and is not suitable for daily use.
Expect breaking changes with every update. Features, settings, stored data, and compatibility may change or stop working without notice.

## About

Tuvora Desktop is the desktop edition of Tuvora ([mobile](https://github.com/paradox-kush/TuvoraMobile) / [TV](https://github.com/paradox-kush/TuvoraTV)). On top of the media-client core it adds the full Tuvora feature set:

- **IPTV**: Xtream Codes, Stalker portal, and M3U (URL or file) playlists with Live TV, VOD, and series
- **Live TV guide** with EPG (external feed matching included)
- **Sports Centre**: fixtures with automatic channel matching
- **Account sync**: self-hosted account login with cross-device sync of profiles, library, watch progress, watched items, addons, and IPTV playlists

## Installation

Download the latest build from [GitHub Releases](https://github.com/paradox-kush/TuvoraDesktop/releases/latest).

- Windows: MSI installer
- macOS: DMG (arm64 + Intel)
- Linux: DEB package, when available

### macOS note

Builds may be unsigned. If macOS blocks the app or reports it as damaged, move the app to `Applications` and run:

```bash
xattr -dr com.apple.quarantine "/Applications/Tuvora.app"
codesign --force --deep --sign - "/Applications/Tuvora.app"
```

## Development

```bash
git clone https://github.com/paradox-kush/TuvoraDesktop.git
cd TuvoraDesktop
./gradlew :composeApp:run
```

Build a release package for the current host:

```bash
./gradlew :composeApp:packageReleaseDistributionForCurrentOS
```

## Credits

Tuvora Desktop is a fork of [Nuvio Desktop](https://github.com/NuvioMedia/NuvioDesktop) by NuvioMedia — all upstream attribution and licenses are preserved. Playback is powered by [mpv](https://mpv.io/) via [MPVKit](https://github.com/NuvioMedia/MPVKit).

## License

See [LICENSE](LICENSE). Upstream attribution preserved from the NuvioMedia project.
