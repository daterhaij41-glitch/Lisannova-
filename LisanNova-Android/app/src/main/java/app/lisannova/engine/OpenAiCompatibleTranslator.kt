package app.lisannova.engine

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Optional translation bridge for an OpenAI-compatible chat-completions endpoint.
 * Only subtitle text is sent; the source video and audio stay on the phone.
 */
class OpenAiCompatibleTranslator(
    private val endpoint: String,
    private val apiKey: String,
    private val model: String
) {
    fun translateBatch(items: List<Subtitle>): List<Subtitle> {
        require(endpoint.startsWith("https://")) {
            "رابط خدمة الترجمة غير صحيح."
        }
        require(apiKey.isNotBlank()) { "أدخل مفتاح خدمة الترجمة أولاً." }
        require(model.isNotBlank()) { "أدخل اسم نموذج الترجمة أولاً." }

        val input = JSONArray()
        items.forEachIndexed { index, item ->
            input.put(JSONObject().put("id", index).put("text", item.text))
        }

        val systemPrompt = """
            You are a professional Turkish-to-Arabic subtitle translator for Turkish drama.
            Translate the Turkish dialogue into natural, concise Modern Standard Arabic.
            Preserve meaning, emotion, names, relationships, and implied context. Do not
            transliterate ordinary Turkish sentences. Do not add explanations or quotation
            marks. Return ONLY a valid JSON array with exactly one object per input item,
            preserving each numeric id, in this schema: [{"id":0,"translation":"..."}].
        """.trimIndent()
        val userPrompt = "Translate these subtitle lines from Turkish to Arabic. Keep one result per id.\n" + input

        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
            .put(JSONObject().put("role", "user").put("content", userPrompt))
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.15)
            .put("messages", messages)
            .toString()

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 25_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }

        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (code !in 200..299) {
                throw IllegalStateException("خدمة الترجمة أعادت الخطأ HTTP $code: ${responseText.take(350)}")
            }
            val response = JSONObject(responseText)
            val content = response.getJSONArray("choices")
                .getJSONObject(0).getJSONObject("message").getString("content")
            val jsonStart = content.indexOf('[')
            val jsonEnd = content.lastIndexOf(']')
            require(jsonStart >= 0 && jsonEnd > jsonStart) { "ردّ الترجمة ليس JSON صالحاً." }
            val translations = JSONArray(content.substring(jsonStart, jsonEnd + 1))
            val byId = HashMap<Int, String>()
            for (i in 0 until translations.length()) {
                val obj = translations.getJSONObject(i)
                byId[obj.getInt("id")] = obj.getString("translation").trim()
            }
            require(items.indices.all { !byId[it].isNullOrBlank() }) {
                "خدمة الترجمة لم تُرجع كل الجمل. لن نصنع فيديو بترجمة ناقصة."
            }
            return items.mapIndexed { index, item -> item.copy(text = byId.getValue(index)) }
        } finally {
            connection.disconnect()
        }
    }
}
