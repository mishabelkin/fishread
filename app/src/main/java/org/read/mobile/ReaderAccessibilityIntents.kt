package org.read.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.Locale

object ReaderAccessibilityIntents {
    const val ACTION_OPEN_READER = "org.read.mobile.action.OPEN_READER"
    const val ACTION_OPEN_CAPTURED_TEXT = "org.read.mobile.action.OPEN_CAPTURED_TEXT"
    const val ACTION_BACKGROUND_READER = "org.read.mobile.action.BACKGROUND_READER"
    const val EXTRA_CAPTURED_TEXT = "org.read.mobile.extra.CAPTURED_TEXT"
    const val EXTRA_CAPTURED_TITLE = "org.read.mobile.extra.CAPTURED_TITLE"
    const val EXTRA_CAPTURED_SOURCE = "org.read.mobile.extra.CAPTURED_SOURCE"
    const val EXTRA_URL_FALLBACK_CAPTURED_TEXT = "org.read.mobile.extra.URL_FALLBACK_CAPTURED_TEXT"
    const val EXTRA_URL_FALLBACK_CAPTURED_TITLE = "org.read.mobile.extra.URL_FALLBACK_CAPTURED_TITLE"
    const val EXTRA_URL_FALLBACK_CAPTURED_SOURCE = "org.read.mobile.extra.URL_FALLBACK_CAPTURED_SOURCE"

    private const val CAPTURED_TEXT_SCHEME = "read-capture"

    fun createOpenCapturedTextIntent(
        context: Context,
        text: String,
        title: String? = null,
        sourceLabel: String? = null
    ): Intent {
        return Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_CAPTURED_TEXT
            putExtra(EXTRA_CAPTURED_TEXT, text)
            putExtra(EXTRA_CAPTURED_TITLE, title)
            putExtra(EXTRA_CAPTURED_SOURCE, sourceLabel)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
    }

    fun createOpenReaderIntent(context: Context): Intent {
        return Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_READER
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
    }

    fun createBackgroundReaderIntent(context: Context): Intent {
        return Intent(context, MainActivity::class.java).apply {
            action = ACTION_BACKGROUND_READER
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
    }

    fun createOpenUrlIntent(
        context: Context,
        url: String,
        fallbackCapture: BrowserOpenFallbackCapture? = null
    ): Intent {
        return Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(url)
            fallbackCapture?.let {
                putExtra(EXTRA_URL_FALLBACK_CAPTURED_TEXT, it.text)
                putExtra(EXTRA_URL_FALLBACK_CAPTURED_TITLE, it.title)
                putExtra(EXTRA_URL_FALLBACK_CAPTURED_SOURCE, it.sourceLabel)
            }
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
    }

    fun createCapturedTextSourceLabel(title: String? = null): String {
        val slug = title
            ?.lowercase(Locale.US)
            ?.replace(Regex("""[^a-z0-9]+"""), "-")
            ?.trim('-')
            ?.takeIf { it.isNotBlank() }
            ?.take(48)
            ?: "captured"

        return "$CAPTURED_TEXT_SCHEME://$slug/${System.currentTimeMillis()}"
    }

    fun isCapturedTextSourceLabel(sourceLabel: String): Boolean {
        return sourceLabel.startsWith("$CAPTURED_TEXT_SCHEME://")
    }
}
