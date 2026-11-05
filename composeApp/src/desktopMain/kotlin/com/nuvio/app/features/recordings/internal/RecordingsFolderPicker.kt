package com.nuvio.app.features.recordings.internal

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val dialogLock = Any()

/**
 * The native folder chooser. It blocks until the user answers, so it runs on the IO dispatcher and never
 * on the UI thread. On macOS that is the AWT [FileDialog] in directory mode, which only
 * `apple.awt.fileDialogForDirectories` enables; that property is process-wide, so it is set just around
 * this dialog. Windows and Linux have no directory mode in FileDialog, so they use a directories-only
 * [JFileChooser] shown on the Swing thread. Null when the user cancelled or the dialog failed.
 */
internal suspend fun pickRecordingsFolder(): File? = withContext(Dispatchers.IO) {
    synchronized(dialogLock) {
        runCatching {
            if (System.getProperty("os.name").orEmpty().lowercase().contains("mac")) pickOnMac() else pickWithChooser()
        }.getOrNull()
    }
}

private fun pickOnMac(): File? {
    val key = "apple.awt.fileDialogForDirectories"
    val previous = System.getProperty(key)
    try {
        System.setProperty(key, "true")
        val dialog = FileDialog(null as Frame?, "Choose a recordings folder", FileDialog.LOAD)
        dialog.isVisible = true
        val name = dialog.file ?: return null
        val dir = dialog.directory ?: return null
        return File(dir, name)
    } finally {
        if (previous == null) System.clearProperty(key) else System.setProperty(key, previous)
    }
}

private fun pickWithChooser(): File? {
    var picked: File? = null
    SwingUtilities.invokeAndWait {
        val chooser = JFileChooser().apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            dialogTitle = "Choose a recordings folder"
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) picked = chooser.selectedFile
    }
    return picked
}
