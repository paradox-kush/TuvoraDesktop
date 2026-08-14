package com.nuvio.app.features.profiles

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.NSItemProvider
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIApplication
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.darwin.NSObject
import platform.posix.memcpy
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.round

/**
 * iOS avatar picking (compile-by-inspection - needs Xcode to run). PHPickerViewController is the
 * out-of-process photo picker, so the app never asks for photo-library permission: the user hands us
 * exactly one image and nothing else.
 *
 * The chosen item's [NSItemProvider] outlives the picker, so the bytes are loaded lazily inside
 * `readBytes` rather than up front - the same shape as the M3U picker's actual.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun pickAvatarImage(onPicked: (PickedAvatarImage?) -> Unit) {
    val root = UIApplication.sharedApplication.keyWindow?.rootViewController
    if (root == null) {
        onPicked(null)
        return
    }
    val configuration = PHPickerConfiguration().apply {
        filter = PHPickerFilter.imagesFilter()
        selectionLimit = 1
    }
    val picker = PHPickerViewController(configuration = configuration)
    val delegate = AvatarPickerDelegate(onPicked)
    // Retain the delegate for the picker's lifetime (the picker holds only a weak ref).
    retainedDelegate = delegate
    picker.delegate = delegate
    root.presentViewController(picker, animated = true, completion = null)
}

// Strong ref so the delegate outlives the callback; cleared when the pick resolves.
private var retainedDelegate: AvatarPickerDelegate? = null

private class AvatarPickerDelegate(
    private val onPicked: (PickedAvatarImage?) -> Unit,
) : NSObject(), PHPickerViewControllerDelegateProtocol {

    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        retainedDelegate = null
        picker.dismissViewControllerAnimated(true, completion = null)

        val provider = (didFinishPicking.firstOrNull() as? PHPickerResult)?.itemProvider
        if (provider == null) {
            onPicked(null)
            return
        }
        onPicked(
            PickedAvatarImage(
                fileName = "avatar.jpg",
                mimeType = "image/jpeg",
                readBytes = { withContext(Dispatchers.Default) { encodeAvatar(provider) } },
            ),
        )
    }
}

/**
 * Loads the picked item, downscales its longest edge to [AVATAR_MAX_DIMENSION_PX] and JPEG-encodes it.
 *
 * Going through UIImage rather than the raw file data is what makes HEIC work - the iPhone camera's
 * default format, which nothing else in the stack would decode - and drawing it into a fresh context
 * bakes in the orientation, so a portrait photo does not upload sideways.
 */
@OptIn(ExperimentalForeignApi::class)
private suspend fun encodeAvatar(provider: NSItemProvider): ByteArray {
    val data = suspendCancellableCoroutine<NSData?> { continuation ->
        provider.loadDataRepresentationForTypeIdentifier("public.image") { loaded, _ ->
            continuation.resume(loaded)
        }
    } ?: error("Could not read the selected image")

    val image = UIImage.imageWithData(data) ?: error("Could not decode the selected image")
    val resized = image.scaledToFit(AVATAR_MAX_DIMENSION_PX.toDouble())
    val jpeg = UIImageJPEGRepresentation(resized, AVATAR_JPEG_QUALITY / 100.0)
        ?: error("Could not encode the selected image")
    return jpeg.toByteArray()
}

/** Returns a copy whose longest edge is at most [maxDimension] points, or the original if smaller. */
@OptIn(ExperimentalForeignApi::class)
private fun UIImage.scaledToFit(maxDimension: Double): UIImage {
    val width = size.useContents { width }
    val height = size.useContents { height }
    val longestEdge = max(width, height)
    if (longestEdge <= maxDimension || longestEdge <= 0.0) return this

    val scale = maxDimension / longestEdge
    val targetWidth = round(width * scale).coerceAtLeast(1.0)
    val targetHeight = round(height * scale).coerceAtLeast(1.0)

    // scale = 1.0 keeps the output in pixels rather than points, so a 3x device does not produce a
    // 1536px image when we asked for 512.
    UIGraphicsBeginImageContextWithOptions(CGSizeMake(targetWidth, targetHeight), false, 1.0)
    drawInRect(CGRectMake(0.0, 0.0, targetWidth, targetHeight))
    val scaled = UIGraphicsGetImageFromCurrentImageContext()
    UIGraphicsEndImageContext()
    return scaled ?: this
}

/** Copies an NSData's bytes into a Kotlin ByteArray via its raw `bytes` pointer + memcpy. */
@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val len = length.toInt()
    if (len == 0) return ByteArray(0)
    val src = bytes ?: return ByteArray(0)
    val out = ByteArray(len)
    out.usePinned { pinned -> memcpy(pinned.addressOf(0), src, len.convert()) }
    return out
}
