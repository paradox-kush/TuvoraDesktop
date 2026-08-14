package com.nuvio.app.features.profiles

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Android avatar picking. The launcher is registered by [MainActivity] at create-time and reused,
 * exactly like [M3UFilePicker] - a Compose item cannot call `registerForActivityResult` itself.
 *
 * PickVisualMedia is the photo picker: on Android 13+ it is the system picker, and below that the
 * support library falls back to a document-picker equivalent. Either way it grants access to the one
 * image the user chose without the app holding a media permission, so nothing here touches
 * READ_MEDIA_IMAGES.
 */
object AvatarImagePicker {
    private var appContext: Context? = null
    private var launcher: ActivityResultLauncher<PickVisualMediaRequest>? = null

    // The pick in flight - set when pickAvatarImage is called, consumed when the result returns.
    private var pending: ((PickedAvatarImage?) -> Unit)? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    /** Registers the PickVisualMedia launcher. Call from MainActivity.onCreate (before setContent). */
    fun bindActivity(activity: ComponentActivity) {
        launcher = activity.registerForActivityResult(
            ActivityResultContracts.PickVisualMedia(),
        ) { uri: Uri? ->
            val cb = pending
            pending = null
            if (uri == null || cb == null) {
                cb?.invoke(null)
                return@registerForActivityResult
            }
            val context = appContext ?: activity.applicationContext
            cb(
                PickedAvatarImage(
                    fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "avatar.jpg",
                    mimeType = "image/jpeg",
                    readBytes = { withContext(Dispatchers.IO) { encodeAvatar(context, uri) } },
                ),
            )
        }
    }

    fun launch(onPicked: (PickedAvatarImage?) -> Unit) {
        val l = launcher
        if (l == null) {
            onPicked(null)
            return
        }
        pending = onPicked
        runCatching {
            l.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }.onFailure {
            pending = null
            onPicked(null)
        }
    }
}

actual fun pickAvatarImage(onPicked: (PickedAvatarImage?) -> Unit) = AvatarImagePicker.launch(onPicked)

/**
 * Decodes [uri], downscales its longest edge to [AVATAR_MAX_DIMENSION_PX] and returns JPEG bytes.
 *
 * ImageDecoder (API 28+) is preferred because it reads HEIC - the default camera format on iPhones,
 * and a common thing to receive and re-share on Android - and applies the EXIF orientation itself.
 * The BitmapFactory path below it has to rotate by hand, or portrait camera shots upload sideways.
 */
private fun encodeAvatar(context: Context, uri: Uri): ByteArray {
    val bitmap = if (Build.VERSION.SDK_INT >= 28) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val longestEdge = max(info.size.width, info.size.height)
            if (longestEdge > AVATAR_MAX_DIMENSION_PX) {
                val scale = AVATAR_MAX_DIMENSION_PX.toFloat() / longestEdge
                decoder.setTargetSize(
                    (info.size.width * scale).roundToInt().coerceAtLeast(1),
                    (info.size.height * scale).roundToInt().coerceAtLeast(1),
                )
            }
            // Software bitmaps only: a hardware bitmap cannot be read back for compression.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        decodeScaledWithBitmapFactory(context, uri)
    } ?: error("Could not decode the selected image")

    return ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, AVATAR_JPEG_QUALITY, out)
        bitmap.recycle()
        out.toByteArray()
    }
}

/** API 24-27 path: sample down on read (bounded memory), scale to the exact cap, then apply EXIF. */
private fun decodeScaledWithBitmapFactory(context: Context, uri: Uri): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val longestEdge = max(bounds.outWidth, bounds.outHeight)
    var sampleSize = 1
    while (longestEdge / (sampleSize * 2) >= AVATAR_MAX_DIMENSION_PX) sampleSize *= 2

    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val decoded = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, options)
    } ?: return null

    val decodedLongest = max(decoded.width, decoded.height)
    val scaled = if (decodedLongest > AVATAR_MAX_DIMENSION_PX) {
        val scale = AVATAR_MAX_DIMENSION_PX.toFloat() / decodedLongest
        Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).roundToInt().coerceAtLeast(1),
            (decoded.height * scale).roundToInt().coerceAtLeast(1),
            true,
        ).also { if (it !== decoded) decoded.recycle() }
    } else {
        decoded
    }

    return applyExifRotation(context, uri, scaled)
}

private fun applyExifRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
    val degrees = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            when (
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
    }.getOrDefault(0f)

    if (degrees == 0f) return bitmap
    val matrix = Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        .also { if (it !== bitmap) bitmap.recycle() }
}
