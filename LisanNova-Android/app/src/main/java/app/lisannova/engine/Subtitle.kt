package app.lisannova.engine

data class Subtitle(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

object SrtWriter {
    fun render(items: List<Subtitle>): String = buildString {
        items.forEachIndexed { index, item ->
            append(index + 1).append('\n')
            append(timestamp(item.startMs)).append(" --> ").append(timestamp(maxOf(item.endMs, item.startMs + 250))).append('\n')
            append(item.text.trim().replace("\r", " ").replace("\n", " ")).append("\n\n")
        }
    }

    private fun timestamp(ms: Long): String {
        val safe = ms.coerceAtLeast(0)
        val hours = safe / 3_600_000
        val minutes = (safe % 3_600_000) / 60_000
        val seconds = (safe % 60_000) / 1_000
        val millis = safe % 1_000
        return "%02d:%02d:%02d,%03d".format(hours, minutes, seconds, millis)
    }
}
