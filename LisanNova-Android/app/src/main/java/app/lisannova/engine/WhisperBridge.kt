package app.lisannova.engine

class WhisperBridge {
    companion object {
        init { System.loadLibrary("lisannova_whisper") }
        @JvmStatic external fun createContext(modelPath: String): Long
        @JvmStatic external fun freeContext(context: Long)
        @JvmStatic external fun transcribeChunk(context: Long, samples: FloatArray, threads: Int): Array<String>
    }
}
