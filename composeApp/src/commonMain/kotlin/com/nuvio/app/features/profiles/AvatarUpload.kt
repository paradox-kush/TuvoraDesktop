package com.nuvio.app.features.profiles

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.isLocalOnly
import com.nuvio.app.core.auth.userId
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.network.USER_AVATAR_BUCKET
import com.nuvio.app.features.watchprogress.WatchProgressClock
import io.github.jan.supabase.storage.storage

/**
 * Uploading a profile picture, as opposed to pasting a link to one.
 *
 * The custom-avatar field used to be the only way to not use a preset: type a URL and hope it points
 * at a real image. That failed people constantly - the links nearest to hand are the ones that don't
 * work (a Discord CDN link is signed and expires within a day, a Drive "share" link and an imgur
 * gallery link serve HTML, not an image), and a URL that doesn't load renders as a blank circle with
 * no error. Uploading removes the hosting problem entirely: the bytes go to our own bucket and the
 * URL we store is one we control.
 *
 * The platform layer picks AND encodes, so everything above it deals in bytes: see [pickAvatarImage].
 */

/** Longest edge an uploaded avatar is downscaled to before encoding. Avatars render at <= 100dp. */
const val AVATAR_MAX_DIMENSION_PX = 512

/** JPEG quality used when re-encoding a picked image. 85 is visually lossless at avatar sizes. */
const val AVATAR_JPEG_QUALITY = 85

/**
 * An image the user chose, already downscaled to [AVATAR_MAX_DIMENSION_PX] and encoded as JPEG by
 * the platform layer.
 *
 * Encoding platform-side is deliberate: a modern phone photo is 4-12 MB of HEIC/JPEG at 4000px, which
 * would blow the bucket's 2 MiB cap, waste the user's data, and hand Coil a bitmap far larger than
 * the 48-100dp it will ever draw. Each platform already has a decoder that does this well, and none
 * of them agree on an API, so the resizing stays behind the expect.
 */
class PickedAvatarImage(
    val fileName: String,
    val mimeType: String,
    val readBytes: suspend () -> ByteArray,
)

/**
 * Opens the platform image picker and invokes [onPicked] with the chosen image (null if the user
 * cancelled or the pick failed).
 *
 * Android = PickVisualMedia through a launcher registered by MainActivity (Compose cannot
 * `registerForActivityResult` from inside a list item, so the activity binds it at create-time, the
 * same way [pickM3UFile] does); iOS = PHPickerViewController; desktop = the native AWT file dialog.
 */
expect fun pickAvatarImage(onPicked: (PickedAvatarImage?) -> Unit)

/**
 * Outcome of an upload, shaped so the edit screen can say something specific went wrong.
 *
 * Failure cases are distinct objects rather than a message string: the wording belongs in
 * composeResources with the other 24 locales, not in a repository.
 */
sealed interface AvatarUploadResult {
    /** [publicUrl] is ready to store on the profile's `avatar_url`. */
    data class Success(val publicUrl: String) : AvatarUploadResult

    /** No account: uploads are per-user and RLS keys off auth.uid(), so there is nowhere to put it. */
    data object RequiresAccount : AvatarUploadResult

    /** The picked image could not be decoded or came back empty. */
    data object Unreadable : AvatarUploadResult

    /** Bigger than the bucket will accept, even after downscaling. */
    data object TooLarge : AvatarUploadResult

    /** Network or server failure. */
    data object Failed : AvatarUploadResult
}

object AvatarUploadRepository {
    private val log = Logger.withTag("AvatarUploadRepository")

    /** Bucket ceiling is 2 MiB; refuse locally too so a huge file fails fast with a clear reason. */
    private const val MAX_UPLOAD_BYTES = 2 * 1024 * 1024

    /**
     * Uploads [picked] as the avatar for [profileIndex] and returns its public URL.
     *
     * The object path is `<uid>/<profileIndex>-<epochMillis>.jpg`. The uid folder is what the storage
     * policies check, and the timestamp is what makes the URL change on every upload - overwriting a
     * fixed path would leave every device (and every HTTP cache between them) showing the previous
     * picture until something evicted it.
     */
    suspend fun uploadAvatar(profileIndex: Int, picked: PickedAvatarImage): AvatarUploadResult {
        val auth = AuthRepository.state.value
        if (auth.isLocalOnly) return AvatarUploadResult.RequiresAccount
        val uid = auth.userId ?: return AvatarUploadResult.RequiresAccount

        return try {
            val bytes = picked.readBytes()
            if (bytes.isEmpty()) return AvatarUploadResult.Unreadable
            if (bytes.size > MAX_UPLOAD_BYTES) return AvatarUploadResult.TooLarge

            val path = "$uid/$profileIndex-${WatchProgressClock.nowEpochMs()}.jpg"
            SupabaseProvider.client.storage
                .from(USER_AVATAR_BUCKET)
                .upload(path, bytes) { upsert = false }

            AvatarUploadResult.Success(SupabaseProvider.selectedBackend.userAvatarStorageUrl(path))
        } catch (e: Throwable) {
            // A dead session here is the same class of failure as a failed profile push: report it
            // rather than letting the screen claim the picture was set.
            AuthRepository.signOutIfSessionInvalid(e, "Avatar upload")
            log.e(e) { "Failed to upload avatar for profile $profileIndex" }
            AvatarUploadResult.Failed
        }
    }

    /**
     * Best-effort removal of a previously uploaded avatar.
     *
     * Only ever deletes objects in our bucket under the signed-in user's own folder, so passing it a
     * preset URL or a pasted third-party link is a no-op rather than a surprise. Failures are
     * swallowed: leaving an orphaned object behind is not worth failing a save the user asked for.
     */
    suspend fun deleteUploadedAvatar(previousUrl: String?) {
        val path = uploadedObjectPath(previousUrl) ?: return
        runCatching {
            SupabaseProvider.client.storage.from(USER_AVATAR_BUCKET).delete(path)
        }.onFailure { e ->
            log.w(e) { "Could not delete replaced avatar object" }
        }
    }

    /**
     * The bucket-relative object path if [url] is an upload belonging to the signed-in user, else null.
     *
     * Matching on the current backend's own prefix means an upload made against a different backend
     * (or a link that merely resembles one) is left alone.
     */
    internal fun uploadedObjectPath(url: String?): String? {
        val value = url?.trim().orEmpty()
        if (value.isEmpty()) return null
        val uid = AuthRepository.state.value.userId?.takeIf { it.isNotBlank() } ?: return null
        val prefix = SupabaseProvider.selectedBackend.userAvatarStorageUrl("")
        if (!value.startsWith(prefix)) return null
        val path = value.removePrefix(prefix)
        return path.takeIf { it.startsWith("$uid/") }
    }
}
