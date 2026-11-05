package com.nuvio.app.features.recordings.api

actual fun installPlatformRecordings() = RecordingsDesktop.install()
actual fun startPlatformRecordings() = RecordingsDesktop.onAppStart()
