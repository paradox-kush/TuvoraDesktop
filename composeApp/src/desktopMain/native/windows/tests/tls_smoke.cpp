// Exercise the production TLS setup with the shipped libmpv, without creating a GPU/WebView.
#include "../player_bridge.cpp"

int wmain(int argc, wchar_t **argv) {
    if (argc != 3) return 3;
    try {
        MpvApi &api = mpvApi();
        mpv_handle *mpv = api.create();
        if (!mpv) throw std::runtime_error("mpv_create failed");
        auto destroy = [&](mpv_handle *value) { api.terminateDestroy(value); };
        std::unique_ptr<mpv_handle, decltype(destroy)> player(mpv, destroy);
        const std::wstring mode(argv[1]);
        api.setOptionString(mpv, "config", "no");
        if (mode == L"baseline") {
            // Reproduce the old setup: verification enabled, no explicit CA bundle.
            api.setOptionString(mpv, "tls-verify", "yes");
        } else {
            configureMpvTls(api, mpv, mode != L"panel");
        }
        api.setOptionString(mpv, "vo", "null");
        api.setOptionString(mpv, "ao", "null");
        api.setOptionString(mpv, "ytdl", "no");
        api.setOptionString(mpv, "idle", "yes");
        api.setOptionString(mpv, "network-timeout", "5");
        if (api.initialize(mpv) < 0) throw std::runtime_error("mpv_initialize failed");
        const std::string url = toUtf8(argv[2]);
        const char *command[] = {"loadfile", url.c_str(), nullptr};
        if (api.command(mpv, command) < 0) throw std::runtime_error("loadfile failed");
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(15);
        while (std::chrono::steady_clock::now() < deadline) {
            const mpv_event *event = api.waitEvent(mpv, 0.1);
            if (event && event->event_id == MPV_EVENT_END_FILE && event->data) {
                const auto *end = static_cast<mpv_event_end_file *>(event->data);
                return end->reason == 0 && end->error >= 0 ? 0 : 1;
            }
        }
        throw std::runtime_error("Timed out waiting for playback outcome");
    } catch (const std::exception &error) {
        std::fprintf(stderr, "%s\n", error.what());
        return 2;
    }
}
