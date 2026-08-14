package com.nuvio.app.features.profiles

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Desktop avatar picking. The native AWT [FileDialog] is the same picker the M3U file source uses,
 * so macOS/Windows get their real open panel rather than a Swing lookalike.
 */
actual fun pickAvatarImage(onPicked: (PickedAvatarImage?) -> Unit) {
    EventQueue.invokeLater {
        val picked: File? = runCatching {
            val dialog = FileDialog(null as Frame?, "Choose a profile picture", FileDialog.LOAD)
            dialog.setFilenameFilter { _, name ->
                val n = name.lowercase()
                n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") ||
                    n.endsWith(".webp") || n.endsWith(".bmp") || n.endsWith(".gif")
            }
            dialog.isVisible = true
            dialog.file?.let { File(dialog.directory ?: "", it) }?.takeIf { it.isFile }
        }.getOrNull()

        if (picked == null) {
            onPicked(null)
        } else {
            onPicked(
                PickedAvatarImage(
                    fileName = picked.name,
                    mimeType = "image/jpeg",
                    readBytes = { withContext(Dispatchers.IO) { encodeAvatar(picked) } },
                ),
            )
        }
    }
}

/**
 * Reads [file], downscales its longest edge to [AVATAR_MAX_DIMENSION_PX] and returns JPEG bytes.
 *
 * The scaled copy is drawn into a TYPE_INT_RGB image on purpose: JPEG has no alpha channel, and
 * writing an image that has one produces either a failed write or inverted colours depending on the
 * ImageIO plugin - so a transparent PNG gets a white background here rather than at the mercy of the
 * encoder.
 */
private fun encodeAvatar(file: File): ByteArray {
    val source = ImageIO.read(file) ?: error("Could not decode the selected image")

    val longestEdge = max(source.width, source.height)
    val scale = if (longestEdge > AVATAR_MAX_DIMENSION_PX) {
        AVATAR_MAX_DIMENSION_PX.toDouble() / longestEdge
    } else {
        1.0
    }
    val targetWidth = (source.width * scale).roundToInt().coerceAtLeast(1)
    val targetHeight = (source.height * scale).roundToInt().coerceAtLeast(1)

    val target = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB)
    val graphics = target.createGraphics()
    try {
        graphics.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BILINEAR,
        )
        graphics.setRenderingHint(
            RenderingHints.KEY_RENDERING,
            RenderingHints.VALUE_RENDER_QUALITY,
        )
        graphics.color = java.awt.Color.WHITE
        graphics.fillRect(0, 0, targetWidth, targetHeight)
        graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null)
    } finally {
        graphics.dispose()
    }

    return ByteArrayOutputStream().use { out ->
        // ImageIO.write() would encode at the plugin's default quality; go through the writer so
        // desktop lands on the same AVATAR_JPEG_QUALITY as Android and iOS.
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
            ?: error("Could not encode the selected image")
        try {
            ImageIO.createImageOutputStream(out).use { stream ->
                writer.output = stream
                val params = writer.defaultWriteParam.apply {
                    if (canWriteCompressed()) {
                        compressionMode = ImageWriteParam.MODE_EXPLICIT
                        compressionQuality = AVATAR_JPEG_QUALITY / 100f
                    }
                }
                writer.write(null, IIOImage(target, null, null), params)
            }
        } finally {
            writer.dispose()
        }
        out.toByteArray()
    }
}
