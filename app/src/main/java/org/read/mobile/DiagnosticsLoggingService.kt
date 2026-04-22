package org.read.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.BatteryManager
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class DiagnosticsLoggingState(
    val isRunning: Boolean,
    val activeFileName: String?,
    val activeFilePath: String?,
    val latestFileName: String?,
    val latestFilePath: String?,
    val sampleCount: Int,
    val startedAtMs: Long?,
    val lastSampleAtMs: Long?,
    val storageDirectoryPath: String
)

internal data class DiagnosticsLogSample(
    val wallClockMs: Long,
    val elapsedSeconds: Long,
    val batteryPercent: Int?,
    val charging: Boolean?,
    val voltageVolts: Double?,
    val currentAmps: Double?,
    val drawWatts: Double?,
    val batteryTempCelsius: Float?,
    val processCpuPercent: Float?,
    val processMemoryMb: Long,
    val threadCount: Int,
    val accessibilityEnabled: Boolean,
    val summaryStatus: String,
    val playbackStatus: String
) {
    fun toCsvLine(): String {
        return listOf(
            formatTimestamp(wallClockMs),
            elapsedSeconds.toString(),
            batteryPercent?.toString().orEmpty(),
            charging?.toString().orEmpty(),
            voltageVolts?.let { String.format(Locale.US, "%.3f", it) }.orEmpty(),
            currentAmps?.let { String.format(Locale.US, "%.6f", it) }.orEmpty(),
            drawWatts?.let { String.format(Locale.US, "%.3f", it) }.orEmpty(),
            batteryTempCelsius?.let { String.format(Locale.US, "%.1f", it) }.orEmpty(),
            processCpuPercent?.let { String.format(Locale.US, "%.2f", it) }.orEmpty(),
            processMemoryMb.toString(),
            threadCount.toString(),
            accessibilityEnabled.toString(),
            summaryStatus,
            playbackStatus
        ).joinToString(",") { csvEscape(it) }
    }
}

internal class DiagnosticsLogRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    private fun storageDirectory(): File {
        val baseDir = appContext.getExternalFilesDir(null) ?: appContext.filesDir
        return File(baseDir, LOG_DIRECTORY_NAME)
    }

    fun currentState(): DiagnosticsLoggingState {
        val directory = storageDirectory()
        val latestLog = directory
            .listFiles { file -> file.isFile && file.extension.equals("csv", ignoreCase = true) }
            ?.maxByOrNull { it.lastModified() }
        return DiagnosticsLoggingState(
            isRunning = prefs.getBoolean(KEY_IS_RUNNING, false),
            activeFileName = prefs.getString(KEY_ACTIVE_FILE_NAME, null),
            activeFilePath = prefs.getString(KEY_ACTIVE_FILE_PATH, null),
            latestFileName = latestLog?.name,
            latestFilePath = latestLog?.absolutePath,
            sampleCount = prefs.getInt(KEY_SAMPLE_COUNT, 0),
            startedAtMs = prefs.getLong(KEY_STARTED_AT_MS, -1L).takeIf { it > 0L },
            lastSampleAtMs = prefs.getLong(KEY_LAST_SAMPLE_AT_MS, -1L).takeIf { it > 0L },
            storageDirectoryPath = directory.absolutePath
        )
    }

    fun startSession(nowMs: Long = System.currentTimeMillis()): DiagnosticsLoggingState {
        synchronized(lock) {
            val directory = storageDirectory().apply { mkdirs() }
            val fileName = "diagnostics_${FILE_TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(nowMs))}.csv"
            val file = File(directory, fileName)
            file.writeText(CSV_HEADER + "\n")
            prefs.edit()
                .putBoolean(KEY_IS_RUNNING, true)
                .putString(KEY_ACTIVE_FILE_NAME, fileName)
                .putString(KEY_ACTIVE_FILE_PATH, file.absolutePath)
                .putInt(KEY_SAMPLE_COUNT, 0)
                .putLong(KEY_STARTED_AT_MS, nowMs)
                .remove(KEY_LAST_SAMPLE_AT_MS)
                .apply()
            return currentState()
        }
    }

    fun appendSample(sample: DiagnosticsLogSample): DiagnosticsLoggingState {
        synchronized(lock) {
            val state = currentState()
            val filePath = state.activeFilePath ?: return state
            File(filePath).appendText(sample.toCsvLine() + "\n")
            prefs.edit()
                .putInt(KEY_SAMPLE_COUNT, state.sampleCount + 1)
                .putLong(KEY_LAST_SAMPLE_AT_MS, sample.wallClockMs)
                .apply()
            return currentState()
        }
    }

    fun stopSession(): DiagnosticsLoggingState {
        synchronized(lock) {
            prefs.edit()
                .putBoolean(KEY_IS_RUNNING, false)
                .apply()
            return currentState()
        }
    }

    fun latestLogFile(): File? {
        val current = currentState().activeFilePath
            ?.let(::File)
            ?.takeIf { it.exists() && it.isFile }
        if (current != null) {
            return current
        }
        val directory = storageDirectory()
        return directory.listFiles { file -> file.isFile && file.extension.equals("csv", ignoreCase = true) }
            ?.maxByOrNull { it.lastModified() }
    }

    fun clearAllLogs(): Int {
        synchronized(lock) {
            val directory = storageDirectory()
            val files = directory.listFiles { file -> file.isFile && file.extension.equals("csv", ignoreCase = true) }
                .orEmpty()
            var removedCount = 0
            files.forEach { file ->
                if (file.delete()) {
                    removedCount += 1
                }
            }
            prefs.edit()
                .remove(KEY_ACTIVE_FILE_NAME)
                .remove(KEY_ACTIVE_FILE_PATH)
                .putInt(KEY_SAMPLE_COUNT, 0)
                .remove(KEY_STARTED_AT_MS)
                .remove(KEY_LAST_SAMPLE_AT_MS)
                .apply()
            return removedCount
        }
    }

    companion object {
        private const val PREFS_NAME = "read_diagnostics_logging"
        private const val LOG_DIRECTORY_NAME = "diagnostics_logs"
        private const val KEY_IS_RUNNING = "is_running"
        private const val KEY_ACTIVE_FILE_NAME = "active_file_name"
        private const val KEY_ACTIVE_FILE_PATH = "active_file_path"
        private const val KEY_SAMPLE_COUNT = "sample_count"
        private const val KEY_STARTED_AT_MS = "started_at_ms"
        private const val KEY_LAST_SAMPLE_AT_MS = "last_sample_at_ms"

        private const val CSV_HEADER =
            "timestamp_iso,elapsed_seconds,battery_percent,is_charging,voltage_volts,current_amps,draw_watts,battery_temp_c,process_cpu_percent,process_memory_mb,thread_count,accessibility_service_enabled,summary_status,playback_status"

        private val FILE_TIMESTAMP_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US)
                .withZone(ZoneId.systemDefault())
    }
}

class DiagnosticsLoggingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repository: DiagnosticsLogRepository
    private var loggingJob: kotlinx.coroutines.Job? = null
    private var startElapsedRealtimeMs: Long = 0L
    private var lastCpuTimeMs: Long = 0L
    private var lastRealtimeMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        repository = DiagnosticsLogRepository(applicationContext)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> {
                stopLogging()
                START_NOT_STICKY
            }
            else -> {
                startLogging()
                START_STICKY
            }
        }
    }

    override fun onDestroy() {
        loggingJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startLogging() {
        if (loggingJob?.isActive == true) {
            updateNotification(repository.currentState())
            return
        }

        val state = repository.startSession()
        startForeground(NOTIFICATION_ID, buildNotification(state))
        startElapsedRealtimeMs = SystemClock.elapsedRealtime()
        lastRealtimeMs = startElapsedRealtimeMs
        lastCpuTimeMs = android.os.Process.getElapsedCpuTime()

        loggingJob = scope.launch {
            while (isActive) {
                val sample = buildSample()
                val currentState = repository.appendSample(sample)
                updateNotification(currentState)
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    private fun stopLogging() {
        loggingJob?.cancel()
        loggingJob = null
        repository.stopSession()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildSample(): DiagnosticsLogSample {
        val wallClockMs = System.currentTimeMillis()
        val currentRealtimeMs = SystemClock.elapsedRealtime()
        val currentCpuTimeMs = android.os.Process.getElapsedCpuTime()
        val batterySnapshot = readBatterySnapshot(applicationContext)
        val processCpuPercent = (currentRealtimeMs - lastRealtimeMs)
            .takeIf { it > 0L }
            ?.let { elapsedMs ->
                val cpuDeltaMs = (currentCpuTimeMs - lastCpuTimeMs).coerceAtLeast(0L)
                ((cpuDeltaMs.toFloat() / elapsedMs.toFloat()) * 100f).coerceAtLeast(0f)
            }
        lastRealtimeMs = currentRealtimeMs
        lastCpuTimeMs = currentCpuTimeMs

        val runtime = Runtime.getRuntime()
        val usedMemoryMb = ((runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L))
            .coerceAtLeast(0L)
        val playbackState = ReaderPlaybackStore.uiState.value
        val summaryState = SummaryDiagnosticsStore.uiState.value
        val playbackStatus = when {
            playbackState.isSpeaking -> "speaking"
            playbackState.hasSegments -> "paused"
            else -> "idle"
        }

        return DiagnosticsLogSample(
            wallClockMs = wallClockMs,
            elapsedSeconds = ((currentRealtimeMs - startElapsedRealtimeMs).coerceAtLeast(0L) / 1000L),
            batteryPercent = batterySnapshot?.levelPercent,
            charging = batterySnapshot?.isCharging,
            voltageVolts = batterySnapshot?.voltageVolts,
            currentAmps = batterySnapshot?.currentAmps,
            drawWatts = batterySnapshot?.currentAmps?.let { amps ->
                batterySnapshot.voltageVolts?.let { volts ->
                    kotlin.math.abs(amps) * volts
                }
            },
            batteryTempCelsius = batterySnapshot?.temperatureCelsius,
            processCpuPercent = processCpuPercent,
            processMemoryMb = usedMemoryMb,
            threadCount = Thread.getAllStackTraces().size,
            accessibilityEnabled = isReadAccessibilityServiceEnabled(applicationContext),
            summaryStatus = summaryState.status,
            playbackStatus = playbackStatus
        )
    }

    private fun updateNotification(state: DiagnosticsLoggingState) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: DiagnosticsLoggingState): android.app.Notification {
        val sampleSuffix = if (state.sampleCount > 0) " · ${state.sampleCount} samples" else ""
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.notification_diagnostics_logging_title))
            .setContentText(getString(R.string.notification_diagnostics_logging_text) + sampleSuffix)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_diagnostics),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_diagnostics_description)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val ACTION_START = "org.read.mobile.action.START_DIAGNOSTICS_LOGGING"
        private const val ACTION_STOP = "org.read.mobile.action.STOP_DIAGNOSTICS_LOGGING"
        private const val NOTIFICATION_CHANNEL_ID = "read_diagnostics_logging"
        private const val NOTIFICATION_ID = 3030
        private const val SAMPLE_INTERVAL_MS = 5_000L

        fun start(context: Context) {
            val intent = Intent(context, DiagnosticsLoggingService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, DiagnosticsLoggingService::class.java).setAction(ACTION_STOP)
            )
        }

        fun shareLatestLog(context: Context): Boolean {
            val appContext = context.applicationContext
            val repository = DiagnosticsLogRepository(appContext)
            val latestFile = repository.latestLogFile() ?: return false
            val authority = "${appContext.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(appContext, authority, latestFile)
            val intent = Intent(Intent.ACTION_SEND)
                .setType("text/csv")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, latestFile.name)
                .putExtra(Intent.EXTRA_TITLE, latestFile.name)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val chooser = Intent.createChooser(
                intent,
                appContext.getString(R.string.share_diagnostics_log_title)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                appContext.startActivity(chooser)
                true
            } catch (_: ActivityNotFoundException) {
                false
            }
        }
    }
}

private data class DiagnosticsBatterySnapshot(
    val levelPercent: Int,
    val isCharging: Boolean,
    val voltageVolts: Double?,
    val currentAmps: Double?,
    val temperatureCelsius: Float?
)

private fun readBatterySnapshot(context: Context): DiagnosticsBatterySnapshot? {
    val batteryIntent = context.registerReceiver(
        null,
        android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
    ) ?: return null

    val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    if (level < 0 || scale <= 0) {
        return null
    }

    val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val isCharging =
        status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

    val voltageMillivolts = batteryIntent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        .takeIf { it > 0 }
    val temperatureTenthsC = batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        .takeIf { it != Int.MIN_VALUE }

    val batteryManager = context.getSystemService(BatteryManager::class.java)
    val averageCurrentMicroamps = batteryManager
        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
        ?.takeIf { it != Int.MIN_VALUE && it != 0 }
    val currentNowMicroamps = batteryManager
        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        ?.takeIf { it != Int.MIN_VALUE && it != 0 }
    val chosenCurrentMicroamps = averageCurrentMicroamps ?: currentNowMicroamps

    return DiagnosticsBatterySnapshot(
        levelPercent = ((level * 100f) / scale.toFloat()).toInt().coerceIn(0, 100),
        isCharging = isCharging,
        voltageVolts = voltageMillivolts?.toDouble()?.div(1000.0),
        currentAmps = chosenCurrentMicroamps?.toDouble()?.div(1_000_000.0),
        temperatureCelsius = temperatureTenthsC?.div(10f)
    )
}

private fun csvEscape(value: String): String {
    val normalized = value.replace("\r", " ").replace("\n", " ")
    return "\"${normalized.replace("\"", "\"\"")}\""
}

private fun formatTimestamp(epochMs: Long): String {
    return DateTimeFormatter.ISO_OFFSET_DATE_TIME
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMs))
}
