package org.read.mobile

import android.content.Context
import android.speech.tts.Voice
import java.util.Locale

data class SpeechVoiceOption(
    val name: String,
    val label: String,
    val detail: String,
    val languageLabel: String
)

internal const val DEFAULT_READER_SPEED = 1.0f
internal const val MIN_READER_SPEED = 0.6f
internal const val MAX_READER_SPEED = 3.0f

internal fun normalizeReaderSpeed(speed: Float): Float =
    speed.coerceIn(MIN_READER_SPEED, MAX_READER_SPEED)

class SpeechSettingsRepository(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("reader_speech_settings", Context.MODE_PRIVATE)

    fun selectedVoiceName(): String? =
        prefs.getString(KEY_SELECTED_VOICE_NAME, null)?.takeIf { it.isNotBlank() }

    fun selectedSpeed(): Float =
        normalizeReaderSpeed(prefs.getFloat(KEY_SELECTED_SPEED, DEFAULT_READER_SPEED))

    fun saveSelectedVoiceName(voiceName: String?) {
        prefs.edit().putString(KEY_SELECTED_VOICE_NAME, voiceName).commit()
    }

    fun saveSelectedSpeed(speed: Float) {
        prefs.edit().putFloat(KEY_SELECTED_SPEED, normalizeReaderSpeed(speed)).commit()
    }

    companion object {
        private const val KEY_SELECTED_VOICE_NAME = "selected_voice_name"
        private const val KEY_SELECTED_SPEED = "selected_speed"
    }
}

internal fun Voice.toSpeechVoiceOption(): SpeechVoiceOption {
    val localeLabel = locale?.displayName?.replaceFirstChar {
        if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
    }.orEmpty()
    val detailParts = buildList {
        if (localeLabel.isNotBlank()) add(localeLabel)
        if (quality > 0) add(when (quality) {
            Voice.QUALITY_VERY_HIGH -> "Very high quality"
            Voice.QUALITY_HIGH -> "High quality"
            Voice.QUALITY_NORMAL -> "Normal quality"
            Voice.QUALITY_LOW -> "Low quality"
            Voice.QUALITY_VERY_LOW -> "Very low quality"
            else -> ""
        }.takeIf { it.isNotBlank() }.orEmpty())
        if (latency > 0) add(when (latency) {
            Voice.LATENCY_VERY_LOW -> "Very low latency"
            Voice.LATENCY_LOW -> "Low latency"
            Voice.LATENCY_NORMAL -> "Normal latency"
            Voice.LATENCY_HIGH -> "High latency"
            Voice.LATENCY_VERY_HIGH -> "Very high latency"
            else -> ""
        }.takeIf { it.isNotBlank() }.orEmpty())
        if (isNetworkConnectionRequired) add("Requires network")
    }.filter { it.isNotBlank() }

    return SpeechVoiceOption(
        name = name,
        label = buildString {
            append(localeLabel.ifBlank { "Voice" })
            val normalizedName = name.substringAfterLast('/').substringAfterLast('-')
            if (normalizedName.isNotBlank() && !localeLabel.contains(normalizedName, ignoreCase = true)) {
                append(" \u2022 ")
                append(normalizedName.replace('_', ' '))
            }
        }.trim(),
        detail = detailParts.joinToString(" \u2022 "),
        languageLabel = localeLabel
    )
}
