package com.yourapp.trans

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer

class VoskRecognizer(modelPath: String, sampleRate: Float = 16000f) {
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private var isListening = false
    private var isPaused = false

    var onPartialResult: ((String) -> Unit)? = null
    var onFinalResult: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    init {
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            model = Model(modelPath)
            recognizer = Recognizer(model, sampleRate)
        } catch (e: Exception) { onError?.invoke(e.message ?: "") }
    }

    fun startListening() {
        if (isListening) return
        if (model == null || recognizer == null) return
        val bufSize = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
        audioRecord?.startRecording()
        isListening = true
        Thread {
            val buf = ShortArray(bufSize)
            while (isListening) {
                if (isPaused) { Thread.sleep(100); continue }
                val read = audioRecord?.read(buf, 0, buf.size) ?: 0
                if (read > 0) {
                    try {
                        if (recognizer?.acceptWaveForm(buf, read) == true) {
                            val t = extractText(recognizer?.result ?: "")
                            if (t.isNotEmpty()) onFinalResult?.invoke(t)
                        } else {
                            val t = extractText(recognizer?.partialResult ?: "")
                            if (t.isNotEmpty()) onPartialResult?.invoke(t)
                        }
                    } catch (e: Exception) { Log.e("Vosk", e.message ?: "") }
                }
            }
        }.start()
    }

    fun pauseListening() { isPaused = true }
    fun resumeListening() { isPaused = false }
    fun stopListening() { isListening = false; try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {} }
    fun reset() { recognizer?.reset() }
    fun release() { stopListening(); try { recognizer?.close(); model?.close() } catch (_: Exception) {} }

    private fun extractText(json: String): String {
        return """"(?:text|partial)"\s*:\s*"([^"]*)"""".toRegex().find(json)?.groupValues?.get(1)?.trim() ?: ""
    }
}
