package me.rerere.rikkahub.context

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.ui.pages.share.handler.sharedPayload
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedPayloadInstrumentedTest {
    @Test fun parcelableStreamAndClipDataAreDeduplicated() {
        val uri = Uri.parse("content://sender/report.pdf")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "Please check")
            clipData = ClipData.newRawUri("PDF", uri)
        }
        val parsed = intent.sharedPayload()
        assertEquals("Please check", parsed.text)
        assertEquals(listOf(uri), parsed.uris)
    }
    @Test fun multipleMixedFilesAndClipOnlyStreamsSurvive() {
        val image = Uri.parse("content://sender/image.png")
        val audio = Uri.parse("content://sender/audio.ogg")
        val pdf = Uri.parse("content://sender/report.pdf")
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(image, audio))
            clipData = ClipData.newRawUri("image", image).also { it.addItem(ClipData.Item(pdf)) }
        }
        assertEquals(listOf(image, audio, pdf), intent.sharedPayload().uris)
    }
    @Test fun clipTextWithoutExtraTextSurvives() {
        val intent = Intent(Intent.ACTION_SEND).apply { clipData = ClipData.newPlainText("url", "https://example.org") }
        assertEquals("https://example.org", intent.sharedPayload().text)
    }
}
