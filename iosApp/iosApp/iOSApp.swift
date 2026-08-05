import SwiftUI
import ComposeApp
import PostHog

private let crashReportsEnabledKey = "sentry_enabled"
private let geoIpDisableProperty = "$geoip_disable"
private let sensitivePropertyNames: Set<String> = [
    "url", "uri", "href", "referrer", "code", "state", "token", "access_token",
    "refresh_token", "authorization", "password", "secret", "cookie", "api_key"
]

private func crashReportsEnabled() -> Bool {
    let defaults = UserDefaults.standard
    return defaults.object(forKey: crashReportsEnabledKey) != nil
        && defaults.bool(forKey: crashReportsEnabledKey)
}

private func isSensitiveProperty(_ key: String) -> Bool {
    let normalized = key.lowercased().replacingOccurrences(of: "-", with: "_")
    return sensitivePropertyNames.contains(normalized)
        || normalized.hasSuffix("_url")
        || normalized.hasSuffix("_uri")
        || normalized.contains("token")
        || normalized.contains("password")
        || normalized.contains("secret")
        || normalized.contains("authorization")
        || normalized.contains("cookie")
}

private func sanitizedPostHogString(_ value: String) -> String {
    let withoutURLs = value.replacingOccurrences(
        of: #"(?i)\b[a-z][a-z0-9+.-]*://\S+"#,
        with: "[REDACTED_URL]",
        options: .regularExpression
    )
    let withoutAuthorization = withoutURLs.replacingOccurrences(
        of: #"(?i)\b(?:bearer|basic)\s+[a-z0-9._~+/=-]+"#,
        with: "[REDACTED_AUTH]",
        options: .regularExpression
    )
    return withoutAuthorization.replacingOccurrences(
        of: #"(?i)\b(code|state|access_token|refresh_token|token|authorization|password|secret)=([^\s&]+)"#,
        with: "$1=[REDACTED]",
        options: .regularExpression
    )
}

private func sanitizedPostHogValue(_ value: Any) -> Any {
    if let string = value as? String {
        return sanitizedPostHogString(string)
    }
    if let dictionary = value as? [String: Any] {
        return sanitizedPostHogProperties(dictionary)
    }
    if let array = value as? [Any] {
        return array.map(sanitizedPostHogValue)
    }
    return value
}

private func sanitizedPostHogProperties(_ properties: [String: Any]) -> [String: Any] {
    var sanitized: [String: Any] = [:]
    for (key, value) in properties where !isSensitiveProperty(key) {
        sanitized[key] = sanitizedPostHogValue(value)
    }
    sanitized[geoIpDisableProperty] = true
    return sanitized
}

private final class PostHogConsentObserver {
    static let shared = PostHogConsentObserver()
    private var observer: NSObjectProtocol?

    func start() {
        guard observer == nil else { return }
        observer = NotificationCenter.default.addObserver(
            forName: UserDefaults.didChangeNotification,
            object: nil,
            queue: .main
        ) { _ in
            if crashReportsEnabled() {
                PostHogSDK.shared.optIn()
            } else {
                PostHogSDK.shared.optOut()
            }
        }
    }
}

@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(OrientationLockAppDelegate.self) private var appDelegate

    init() {
        // Keep iOS crash/error reporting in the same PostHog project as Android and TV.
        // The project token is a public client key; provider/account data is never attached.
        let config = PostHogConfig(
            projectToken: "phc_o824qv3fcxKW9NvF4K6mYKX3rScK5CBQzrSx4RQ5b6ye",
            host: "https://us.i.posthog.com"
        )
        config.errorTrackingConfig.autoCapture = true
        config.optOut = !crashReportsEnabled()
        config.captureApplicationLifecycleEvents = false
        config.captureScreenViews = false
        config.sendFeatureFlagEvent = false
        config.preloadFeatureFlags = false
        config.surveys = false
        config.sessionReplay = false
        config.sessionReplayConfig.captureNetworkTelemetry = false
        config.sessionReplayConfig.captureLogs = false
        config.sessionReplayConfig.screenshotMode = false
        config.tracingHeaders = []
        config.logs.setBeforeSend { _ in nil }
        config.setBeforeSend { event in
            if event.event.caseInsensitiveCompare("Deep Link Opened") == .orderedSame {
                return nil
            }
            event.properties = sanitizedPostHogProperties(event.properties)
            return event
        }
        config.flushIntervalSeconds = 10
        PostHogSDK.shared.setup(config)
        PostHogSDK.shared.register([geoIpDisableProperty: true])
        if crashReportsEnabled() {
            PostHogSDK.shared.optIn()
        } else {
            PostHogSDK.shared.optOut()
        }
        PostHogConsentObserver.shared.start()

        if #available(iOS 14.0, *) {
            // MetricKit supplies delayed hangs and resource failures that exception
            // autocapture cannot observe. The singleton remains subscribed for app life.
            MetricKitReliabilityReporter.shared.start()
        }
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .preferredColorScheme(.dark)
                .onOpenURL { url in
                    AppUrlBridgeKt.handleAppUrl(url: url.absoluteString)
                }
        }
    }
}
