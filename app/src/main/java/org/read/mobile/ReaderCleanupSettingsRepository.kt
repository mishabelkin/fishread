package org.read.mobile

import android.content.Context

enum class ReaderCleanupMode(val storageValue: String) {
    Off("off"),
    WebAndPdf("web_and_pdf");

    companion object {
        fun fromStorage(value: String?): ReaderCleanupMode {
            return when (value) {
                null -> WebAndPdf
                "web_only", "web_and_pdf_experimental", WebAndPdf.storageValue -> WebAndPdf
                Off.storageValue -> Off
                else -> WebAndPdf
            }
        }
    }
}

enum class ReaderCleanupAccelerationMode(val storageValue: String) {
    Cpu("cpu"),
    Auto("auto"),
    Gpu("gpu"),
    Npu("npu");

    companion object {
        fun fromStorage(value: String?): ReaderCleanupAccelerationMode {
            return when (value) {
                Auto.storageValue -> Auto
                Gpu.storageValue -> Gpu
                Npu.storageValue -> Npu
                Cpu.storageValue, null -> Cpu
                else -> Cpu
            }
        }
    }
}

data class ReaderCleanupSettings(
    val mode: ReaderCleanupMode,
    val selectedModelId: String?,
    val sharedModelFolderUri: String?,
    val accelerationMode: ReaderCleanupAccelerationMode,
    val diagnosticsEnabled: Boolean
)

class ReaderCleanupSettingsRepository(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("reader_cleanup_settings", Context.MODE_PRIVATE)

    fun settings(): ReaderCleanupSettings {
        return ReaderCleanupSettings(
            mode = ReaderCleanupMode.fromStorage(prefs.getString(KEY_MODE, null)),
            selectedModelId = prefs.getString(KEY_SELECTED_MODEL_ID, null),
            sharedModelFolderUri = prefs.getString(KEY_SHARED_MODEL_FOLDER_URI, null),
            accelerationMode = ReaderCleanupAccelerationMode.fromStorage(
                prefs.getString(KEY_ACCELERATION_MODE, null)
            ),
            diagnosticsEnabled = prefs.getBoolean(KEY_DIAGNOSTICS_ENABLED, false)
        )
    }

    fun cleanupMode(): ReaderCleanupMode = settings().mode

    fun selectedModelId(): String? = settings().selectedModelId

    fun sharedModelFolderUri(): String? = settings().sharedModelFolderUri

    fun cleanupAccelerationMode(): ReaderCleanupAccelerationMode = settings().accelerationMode

    fun cleanupDiagnosticsEnabled(): Boolean = settings().diagnosticsEnabled

    fun saveCleanupMode(mode: ReaderCleanupMode) {
        prefs.edit().putString(KEY_MODE, mode.storageValue).commit()
    }

    fun saveSelectedModelId(modelId: String?) {
        prefs.edit().apply {
            if (modelId.isNullOrBlank()) {
                remove(KEY_SELECTED_MODEL_ID)
            } else {
                putString(KEY_SELECTED_MODEL_ID, modelId)
            }
        }.commit()
    }

    fun saveSharedModelFolderUri(uri: String?) {
        prefs.edit().apply {
            if (uri.isNullOrBlank()) {
                remove(KEY_SHARED_MODEL_FOLDER_URI)
            } else {
                putString(KEY_SHARED_MODEL_FOLDER_URI, uri)
            }
        }.commit()
    }

    fun saveCleanupAccelerationMode(mode: ReaderCleanupAccelerationMode) {
        prefs.edit().putString(KEY_ACCELERATION_MODE, mode.storageValue).commit()
    }

    fun saveCleanupDiagnosticsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DIAGNOSTICS_ENABLED, enabled).commit()
    }

    companion object {
        private const val KEY_MODE = "cleanup_mode"
        private const val KEY_SELECTED_MODEL_ID = "cleanup_selected_model_id"
        private const val KEY_SHARED_MODEL_FOLDER_URI = "cleanup_shared_model_folder_uri"
        private const val KEY_ACCELERATION_MODE = "cleanup_acceleration_mode"
        private const val KEY_DIAGNOSTICS_ENABLED = "cleanup_diagnostics_enabled"
    }
}
