package app.lisannova.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.lisannova.service.ProcessingService
import app.lisannova.engine.ApiKeyVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private var videoFile: File? = null
    private var modelFile: File? = null
    private lateinit var videoLabel: TextView
    private lateinit var modelLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var progress: ProgressBar
    private lateinit var startButton: Button
    private lateinit var translationLabel: TextView
    private var receiverRegistered = false

    private val videoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importPickedFile(uri, isModel = false)
    }
    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importPickedFile(uri, isModel = true)
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ProcessingService.ACTION_STATUS) return
            statusLabel.text = intent.getStringExtra(ProcessingService.EXTRA_STATUS) ?: ""
            progress.progress = intent.getIntExtra(ProcessingService.EXTRA_PROGRESS, 0)
            val result = intent.getStringExtra(ProcessingService.EXTRA_RESULT_URI)
            if (!result.isNullOrBlank()) statusLabel.text = "${statusLabel.text}\n\nملف الفيديو: Movies/LisanNova"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        refreshSettingsLabel()
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                statusReceiver,
                IntentFilter(ProcessingService.ACTION_STATUS),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(statusReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    private fun buildUi() {
        val page = ScrollView(this).apply { setBackgroundColor(0xFF10151B.toInt()) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(28))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        page.addView(root)

        val logo = TextView(this).apply {
            text = "LISAN NOVA"
            textSize = 27f
            setTextColor(0xFF6DDBCA.toInt())
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
        }
        root.addView(logo, matchWrap())

        root.addView(TextView(this).apply {
            text = "ترجمة الحلقات التركية إلى العربية"
            textSize = 17f
            setTextColor(0xFFF2F5F8.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(4))
        }, matchWrap())
        root.addView(TextView(this).apply {
            text = "التعرّف على الكلام محلياً، ثم تجهيز ترجمة عربية وملف MP4 جديد."
            textSize = 13f
            setTextColor(0xFFAAB6C2.toInt())
            gravity = Gravity.CENTER
        }, matchWrap())

        root.addView(space(24))
        videoLabel = addFileCard(root, "1. ملف الحلقة", "لم تختر فيديو بعد", "اختيار الفيديو") {
            videoPicker.launch(arrayOf("video/*"))
        }
        root.addView(space(12))
        modelLabel = addFileCard(root, "2. نموذج التعرف على الكلام", "اختر ملف Whisper بصيغة GGML (.bin)", "اختيار نموذج Whisper") {
            modelPicker.launch(arrayOf("application/octet-stream", "application/x-binary", "*/*"))
        }
        root.addView(space(12))

        val settingsButton = Button(this).apply {
            text = "إعداد محرك الترجمة العربية"
            setOnClickListener { showTranslationSettings() }
        }
        root.addView(settingsButton, matchWidth())
        translationLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF9AA8B5.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, 0)
        }
        root.addView(translationLabel, matchWrap())

        root.addView(space(18))
        startButton = Button(this).apply {
            text = "ابدأ المعالجة"
            isEnabled = false
            setOnClickListener { startProcessing() }
        }
        root.addView(startButton, matchWidth())

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.VISIBLE
        }
        val progressParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8))
        progressParams.topMargin = dp(18)
        root.addView(progress, progressParams)

        statusLabel = TextView(this).apply {
            text = "اختر الحلقة ونموذج Whisper للبدء."
            textSize = 13f
            setTextColor(0xFFD7E0E8.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(12), dp(4), dp(4))
        }
        root.addView(statusLabel, matchWrap())

        root.addView(space(20))
        root.addView(TextView(this).apply {
            text = "ملاحظة: التعرف على الكلام يعمل محلياً. إنتاج ترجمة عربية في هذه النسخة يحتاج إعداد خدمة ترجمة متوافقة؛ لا نرسل الفيديو أو الصوت، بل نص الجمل فقط عند تفعيلها."
            textSize = 11f
            setTextColor(0xFF8593A0.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(12))
        }, matchWrap())

        setContentView(page)
    }

    private fun addFileCard(parent: LinearLayout, title: String, initial: String, buttonText: String, action: () -> Unit): TextView {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundColor(0xFF1C252E.toInt())
        }
        parent.addView(card, matchWidth())
        card.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(0xFFF3F6F8.toInt())
        }, matchWrap())
        val label = TextView(this).apply {
            text = initial
            textSize = 12f
            setTextColor(0xFFAAB6C2.toInt())
            setPadding(0, dp(7), 0, dp(7))
        }
        card.addView(label, matchWrap())
        card.addView(Button(this).apply {
            text = buttonText
            setOnClickListener { action() }
        }, matchWidth())
        return label
    }

    private fun importPickedFile(uri: Uri, isModel: Boolean) {
        statusLabel.text = if (isModel) "نسخ نموذج Whisper إلى مساحة التطبيق…" else "نسخ الفيديو إلى مساحة التطبيق…"
        startButton.isEnabled = false
        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { copyUriToPrivateStorage(uri, isModel) }
                if (isModel) {
                    modelFile = file
                    modelLabel.text = "${file.name} (${formatBytes(file.length())})"
                } else {
                    videoFile = file
                    videoLabel.text = "${file.name} (${formatBytes(file.length())})"
                }
                statusLabel.text = "تم استيراد الملف بنجاح."
            } catch (t: Throwable) {
                statusLabel.text = "تعذر استيراد الملف: ${t.message}"
            }
            startButton.isEnabled = videoFile?.exists() == true && modelFile?.exists() == true
        }
    }

    private fun copyUriToPrivateStorage(uri: Uri, isModel: Boolean): File {
        val name = queryDisplayName(uri) ?: if (isModel) "whisper-model.bin" else "episode.mp4"
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100)
        val folder = File(filesDir, if (isModel) "models" else "videos").apply { mkdirs() }
        val target = File(folder, safeName)
        if (target.exists() && target.length() > 0) return target
        val temp = File(folder, "$safeName.copying")
        val source = requireNotNull(contentResolver.openInputStream(uri)) { "لم أستطع فتح الملف المحدد." }
        source.use { input ->
            FileOutputStream(temp).use { output ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
                output.fd.sync()
            }
        }
        require(temp.length() > 0) { "الملف المستورد فارغ." }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        return target
    }

    private fun queryDisplayName(uri: Uri): String? = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

    private fun showTranslationSettings() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val endpoint = editField("رابط API الكامل، ينتهي بـ /chat/completions", prefs.getString(KEY_ENDPOINT, "") ?: "")
        val model = editField("اسم نموذج الترجمة", prefs.getString(KEY_TRANSLATION_MODEL, "") ?: "")
        val storedKey = runCatching { ApiKeyVault.decrypt(prefs.getString(KEY_API_KEY, "") ?: "") }.getOrDefault("")
        val key = editField("مفتاح API", storedKey).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        fields.addView(endpoint)
        fields.addView(model)
        fields.addView(key)

        AlertDialog.Builder(this)
            .setTitle("إعداد الترجمة العربية")
            .setMessage("يمكنك استعمال أي خدمة تدعم واجهة OpenAI Chat Completions. هذه النسخة ترسل نصوص الترجمة فقط إلى الخدمة، وليس ملف الفيديو أو الصوت. اترك الحقول فارغة للتعرف المحلي دون ترجمة سحابية.")
            .setView(fields)
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("حفظ") { _, _ ->
                prefs.edit()
                    .putString(KEY_ENDPOINT, endpoint.text.toString().trim())
                    .putString(KEY_TRANSLATION_MODEL, model.text.toString().trim())
                    .putString(KEY_API_KEY, ApiKeyVault.encrypt(key.text.toString().trim()))
                    .apply()
                refreshSettingsLabel()
            }
            .show()
    }

    private fun editField(hintText: String, initialValue: String): EditText = EditText(this).apply {
        hint = hintText
        setText(initialValue)
        textSize = 13f
        setSingleLine(true)
    }

    private fun refreshSettingsLabel() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ready = !prefs.getString(KEY_ENDPOINT, "").isNullOrBlank() &&
            !prefs.getString(KEY_API_KEY, "").isNullOrBlank() &&
            !prefs.getString(KEY_TRANSLATION_MODEL, "").isNullOrBlank()
        translationLabel.text = if (ready) "محرك الترجمة مُعدّ" else "لم يتم إعداد خدمة الترجمة؛ ستعمل مرحلة التعرف المحلية فقط."
    }

    private fun startProcessing() {
        val video = videoFile ?: return
        val model = modelFile ?: return
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val intent = Intent(this, ProcessingService::class.java)
            .putExtra(ProcessingService.EXTRA_VIDEO, video.absolutePath)
            .putExtra(ProcessingService.EXTRA_MODEL, model.absolutePath)
            .putExtra(ProcessingService.EXTRA_ENDPOINT, prefs.getString(KEY_ENDPOINT, ""))
            .putExtra(ProcessingService.EXTRA_API_KEY, runCatching { ApiKeyVault.decrypt(prefs.getString(KEY_API_KEY, "") ?: "") }.getOrDefault(""))
            .putExtra(ProcessingService.EXTRA_TRANSLATION_MODEL, prefs.getString(KEY_TRANSLATION_MODEL, ""))
        ContextCompat.startForegroundService(this, intent)
        statusLabel.text = "بدأت المعالجة. اترك التطبيق يعمل ولا تغلقه بالقوة."
        progress.progress = 1
    }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) "%.2f GB".format(Locale.US, mb / 1024.0) else "%.0f MB".format(Locale.US, mb)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun matchWidth() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    private fun matchWrap() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    private fun space(heightDp: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(heightDp)) }

    companion object {
        private const val PREFS = "lisannova_preferences"
        private const val KEY_ENDPOINT = "translation_endpoint"
        private const val KEY_API_KEY = "translation_api_key"
        private const val KEY_TRANSLATION_MODEL = "translation_model"
    }
}
