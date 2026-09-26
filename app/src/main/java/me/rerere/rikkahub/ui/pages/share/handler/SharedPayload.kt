package me.rerere.rikkahub.ui.pages.share.handler

import android.content.Intent
import android.net.Uri
import android.os.Build

/** Read Parcelable streams as well as ClipData; senders frequently populate both. */
data class SharedPayload(val text: String, val uris: List<Uri>)

fun normalizeSharedUris(streams: List<String>, clips: List<String>): List<String> =
    (streams + clips).filter { it.startsWith("content://") || it.startsWith("file://") }.distinct()

@Suppress("DEPRECATION")
fun Intent.sharedPayload(): SharedPayload {
    val streams = when (action) {
        Intent.ACTION_SEND_MULTIPLE -> if (Build.VERSION.SDK_INT >= 33) {
            getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        else -> listOfNotNull(if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
    }
    val clipUris = clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } }.orEmpty()
    val texts = listOfNotNull(getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()).ifEmpty {
        clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).text?.toString() } }.orEmpty()
    }
    return SharedPayload(texts.distinct().joinToString("\n"), normalizeSharedUris(streams.map(Uri::toString), clipUris.map(Uri::toString)).map(Uri::parse))
}
