package app.lisannova.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.lisannova.R
import app.lisannova.engine.OpenAiCompatibleTranslator
import app.lisannova.engine.Subtitle
import app.lisannova.engine.SrtWriter
import app.lisannova.engine.WhisperBridge
import app.lisannova.ui.MainActivity
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

class ProcessingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification("بدأ تجهيز الحلقة…"),
            if (Build.VERSION.SDK_INT >= 29) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_NOT_STICKY
        val videoPath = intent?.getStringExtra(EXTRA_VIDEO).orEmpty()
        val modelPath = intent?.getStringExtra(EXTRA_MODEL).orEmpty()
        val endpoint = intent?.getStringExtra(EXTRA_ENDPOINT).orEmpty()
        val apiKey = intent?.getStringExtra(EXTRA_API_KEY).orEmpty()
        val translationModel = intent?.getStringExtra(EXTRA_TRANSLATION_MODEL).orEmpty()
        if (videoPath.isBlank() || modelPath.isBlank()) {
            report("اختر الفيديو ونموذج Whisper أولاً.", 0)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        running = true
        scope.launch {
            try {
                runPipeline(File(videoPath), File(modelPath), endpoint, apiKey, translationModel)
            } catch (t: Throwable) {
                report("توقفت المعالجة: ${t.message ?: t.javaClass.simpleName}", 0)
            } finally {
                running = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runPipeline(video: File, model: File, endpoint: String, apiKey: String, translationModel: String) {
        require(video.isFile && video.length() > 0) { "ملف الفيديو غير موجود أو فارغ." }
        require(model.isFile && model.length() > 0) { "ملف نموذج Whisper غير موجود أو فارغ." }
        val work = File(filesDir, "work").apply { mkdirs() }
        val pcm = File(work, "audio_16k_mono.pcm")
        val sourceName = video.nameWithoutExtension.ifBlank { "episode" }
        val turkishSrt = File(work, "${sourceName}_turkish.srt")
        val arabicSrt = File(work, "${sourceName}_arabic.srt")
        val outputVideo = File(work, "${sourceName}_arabic_subtitled.mp4")

        report("1/4 استخراج الصوت من الفيديو…", 5)
        runFfmpeg(
            "-hide_banner -loglevel error -y -i ${q(video.absolutePath)} -map 0:a:0 -vn -ac 1 -ar 16000 -f s16le ${q(pcm.absolutePath)}",
            "تعذر استخراج المسار الصوتي من الفيديو."
        )
        require(pcm.length() >= 32_000) { "لم نعثر على صوت كافٍ في الفيديو." }

        report("2/4 تحميل نموذج Whisper…", 10)
        val context = WhisperBridge.createContext(model.absolutePath)
        require(context != 0L) { "لم يستطع Whisper فتح النموذج. تحقق من أن الملف بصيغة GGML المتوافقة." }
        val segments = try {
            transcribePcm(pcm, context)
        } finally {
            WhisperBridge.freeContext(context)
        }
        require(segments.isNotEmpty()) { "لم يتم التعرف على كلام تركي في هذا الفيديو." }
        turkishSrt.writeText(SrtWriter.render(segments), Charsets.UTF_8)
        exportText(turkishSrt, "${sourceName}_turkish.srt")

        if (endpoint.isBlank() || apiKey.isBlank() || translationModel.isBlank()) {
            report("اكتمل التعرف على الكلام وحُفظ ملف SRT التركي. لإنتاج فيديو عربي، أضف إعدادات محرك الترجمة من التطبيق.", 100)
            return
        }

        report("3/4 ترجمة الجمل إلى العربية…", 72)
        val translator = OpenAiCompatibleTranslator(endpoint, apiKey, translationModel)
        val translated = ArrayList<Subtitle>(segments.size)
        val batchSize = 8
        val batches = segments.chunked(batchSize)
        batches.forEachIndexed { index, batch ->
            translated += translator.translateBatch(batch)
            val progress = 72 + ((index + 1) * 20 / batches.size.coerceAtLeast(1))
            report("3/4 ترجمة الجمل إلى العربية… (${index + 1}/${batches.size})", progress)
        }
        arabicSrt.writeText(SrtWriter.render(translated), Charsets.UTF_8)
        exportText(arabicSrt, "${sourceName}_arabic.srt")

        report("4/4 حرق الترجمة وإنتاج MP4…", 94)
        val subtitleFilter = "subtitles=filename='${filterPath(arabicSrt.absolutePath)}':fontsdir='/system/fonts':force_style='FontName=Noto Sans Arabic,FontSize=22,PrimaryColour=&H00FFFFFF,OutlineColour=&H00000000,BorderStyle=1,Outline=2,Shadow=0,Alignment=2,MarginV=34'"
        val burnCommand = "-hide_banner -loglevel error -y -i ${q(video.absolutePath)} -vf ${q(subtitleFilter)} -c:v h264_mediacodec -b:v 2500k -c:a aac -b:a 128k -movflags +faststart ${q(outputVideo.absolutePath)}"
        try {
            runFfmpeg(burnCommand, "تعذر حرق الترجمة داخل الفيديو.")
        } catch (e: Throwable) {
            // Fallback to the broadly available MPEG-4 software encoder.
            val fallback = "-hide_banner -loglevel error -y -i ${q(video.absolutePath)} -vf ${q(subtitleFilter)} -c:v mpeg4 -q:v 4 -c:a aac -b:a 128k -movflags +faststart ${q(outputVideo.absolutePath)}"
            runFfmpeg(fallback, "فشل ترميز الفيديو بالطريقتين المتاحتين.")
        }
        require(outputVideo.isFile && outputVideo.length() > 100_000) { "ملف MP4 الناتج غير مكتمل." }
        val savedUri = exportVideo(outputVideo, "${sourceName}_AR.mp4")
        report("اكتملت المعالجة. حُفظ الفيديو العربي في Movies/LisanNova.", 100, savedUri)
    }

    private suspend fun transcribePcm(pcm: File, context: Long): List<Subtitle> = withContext(Dispatchers.IO) {
        val sampleRate = 16_000L
        val chunkSamples = 30L * sampleRate
        val stepSamples = 26L * sampleRate
        val totalSamples = pcm.length() / 2L
        val totalChunks = ((totalSamples + stepSamples - 1) / stepSamples).toInt().coerceAtLeast(1)
        val results = ArrayList<Subtitle>()
        RandomAccessFile(pcm, "r").use { input ->
            var chunkIndex = 0
            var startSample = 0L
            while (startSample < totalSamples) {
                val count = minOf(chunkSamples, totalSamples - startSample).toInt()
                val data = ByteArray(count * 2)
                input.seek(startSample * 2)
                input.readFully(data)
                val floats = FloatArray(count)
                var i = 0
                while (i < count) {
                    val lo = data[i * 2].toInt() and 0xff
                    val hi = data[i * 2 + 1].toInt()
                    val sample = (hi shl 8) or lo
                    floats[i] = sample / 32768.0f
                    i++
                }
                val rows = WhisperBridge.transcribeChunk(context, floats, 3)
                val offsetMs = startSample * 1000L / sampleRate
                rows.forEach { row ->
                    val parts = row.split('\t', limit = 3)
                    if (parts.size == 3) {
                        val localStart = parts[0].toLongOrNull() ?: 0L
                        val localEnd = parts[1].toLongOrNull() ?: localStart + 250L
                        val text = parts[2].trim()
                        // Keep boundary lines from both windows. Drop only an exact repeated line
                        // whose start time is very close; do not discard every early line in an overlap.
                        if (text.isNotBlank()) {
                            val item = Subtitle(offsetMs + localStart, offsetMs + localEnd, text)
                            val normalized = normalizeForOverlap(text)
                            val duplicate = results.asReversed().take(12).any { previous ->
                                kotlin.math.abs(previous.startMs - item.startMs) <= 3_500L &&
                                    normalizeForOverlap(previous.text) == normalized
                            }
                            if (!duplicate) results += item
                        }
                    }
                }
                chunkIndex++
                val percent = 12 + (chunkIndex * 58 / totalChunks).coerceAtMost(58)
                report("2/4 التعرف على الكلام التركي… ($chunkIndex/$totalChunks)", percent)
                if (startSample + chunkSamples >= totalSamples) break
                startSample += stepSamples
            }
        }
        results.sortedBy { it.startMs }
    }

    private fun normalizeForOverlap(text: String): String = text
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    private fun runFfmpeg(command: String, errorMessage: String) {
        val session = FFmpegKit.execute(command)
        if (!ReturnCode.isSuccess(session.returnCode)) {
            val details = session.failStackTrace ?: session.allLogsAsString
            throw IllegalStateException("$errorMessage ${details.takeLast(700)}")
        }
    }

    private fun exportText(file: File, displayName: String) {
        if (Build.VERSION.SDK_INT < 29) return
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "application/x-subrip")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LisanNova")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        try {
            contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        } catch (t: Throwable) {
            contentResolver.delete(uri, null, null)
            throw t
        }
    }

    private fun exportVideo(file: File, displayName: String): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/LisanNova")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            return uri
        } catch (t: Throwable) {
            contentResolver.delete(uri, null, null)
            throw t
        }
    }

    private fun report(message: String, progress: Int, resultUri: Uri? = null) {
        val intent = Intent(ACTION_STATUS).setPackage(packageName)
            .putExtra(EXTRA_STATUS, message)
            .putExtra(EXTRA_PROGRESS, progress)
        resultUri?.let { intent.putExtra(EXTRA_RESULT_URI, it.toString()) }
        sendBroadcast(intent)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun buildNotification(message: String): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle("LisanNova")
        .setContentText(message.take(120))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "LisanNova processing", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun q(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    private fun filterPath(value: String): String = value.replace("\\", "\\\\").replace(":", "\\:").replace("'", "\\'")

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STATUS = "app.lisannova.PROCESS_STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_RESULT_URI = "result_uri"
        const val EXTRA_VIDEO = "video_path"
        const val EXTRA_MODEL = "model_path"
        const val EXTRA_ENDPOINT = "translation_endpoint"
        const val EXTRA_API_KEY = "translation_key"
        const val EXTRA_TRANSLATION_MODEL = "translation_model"
        private const val CHANNEL_ID = "lisannova_processing"
        private const val NOTIFICATION_ID = 3107
    }
}
