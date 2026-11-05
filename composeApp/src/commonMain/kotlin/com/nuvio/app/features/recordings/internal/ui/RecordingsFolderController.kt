package com.nuvio.app.features.recordings.internal.ui

import com.nuvio.app.features.recordings.api.FolderChangeResult
import com.nuvio.app.features.recordings.api.RecordingsFolderControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** State holder for the "Recordings folder" block: opens the chooser, remembers whether the last pick was refused. */
internal class RecordingsFolderController(
    private val control: RecordingsFolderControl,
    private val scope: CoroutineScope,
) {
    val path: StateFlow<String> = control.folderPath

    private val _notWritable = MutableStateFlow(false)
    val notWritable: StateFlow<Boolean> = _notWritable

    private val _choosing = MutableStateFlow(false)
    val choosing: StateFlow<Boolean> = _choosing

    fun change() {
        if (!_choosing.compareAndSet(expect = false, update = true)) return
        scope.launch {
            try {
                when (control.chooseNewFolder()) {
                    is FolderChangeResult.Changed -> _notWritable.value = false
                    is FolderChangeResult.NotWritable -> _notWritable.value = true
                    FolderChangeResult.Cancelled -> Unit
                }
            } finally {
                _choosing.value = false
            }
        }
    }
}
