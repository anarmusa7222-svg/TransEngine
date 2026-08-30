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
    private var listeningThread: Thread? = null

    var onPartialResult: ((String) -> Unit)? = null
    var onFinalResult: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    init {
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            model = Model(modelPath)
            recognizer = Recognizer(model, sampleRate)
            Log.i("VoskRecognizer", "✅ Vosk modeli başarıyla yüklendi: $modelPath")
        } catch (e: Exception) { 
            Log.e("VoskRecognizer", "❌ Vosk başlatma hatası: ${e.message}")
            onError?.invoke(e.message ?: "") 
        }
    }

    fun startListening() {
        if (isListening) return
        if (model == null || recognizer == null) {
            Log.e("VoskRecognizer", "Model veya recognizer null")
            return
        }
        try {
            val bufSize = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
            audioRecord?.startRecording()
            isListening = true
            
            listeningThread = Thread {
                val buf = ShortArray(bufSize)
                while (isListening) {
                    if (isPaused) { Thread.sleep(100); continue }
                    try {
                        val read = audioRecord?.read(buf, 0, buf.size) ?: 0
                        if (read > 0) {
                            if (recognizer?.acceptWaveForm(buf, read) == true) {
                                val t = extractText(recognizer?.result ?: "")
                                if (t.isNotEmpty()) onFinalResult?.invoke(t)
                            } else {
                                val t = extractText(recognizer?.partialResult ?: "")
                                if (t.isNotEmpty()) onPartialResult?.invoke(t)
                            }
                        }
                    } catch (e: Exception) { Log.e("VoskRecognizer", "Dinleme hatası: ${e.message}") }
                }
            }
            listeningThread?.start()
            Log.i("VoskRecognizer", "✅ Dinleme başladı")
        } catch (e: Exception) {
            Log.e("VoskRecognizer", "Dinleme başlatma hatası: ${e.message}")
            onError?.invoke(e.message ?: "")
        }
    }

    fun pauseListening() { isPaused = true }
    fun resumeListening() { isPaused = false }
    fun stopListening() { 
        isListening = false
        try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {} 
    }
    fun reset() { try { recognizer?.reset() } catch (_: Exception) {} }
    fun release() { 
        stopListening()
        try { recognizer?.close(); model?.close() } catch (_: Exception) {} 
        Log.i("VoskRecognizer", "✅ Vosk serbest bırakıldı")
    }

    private fun extractText(json: String): String {
        return \"\"\"(?:text|partial)\"\s*:\s\"([^\"]*)\"\"\".toRegex().find(json)?.groupValues?.get(1)?.trim() ?: ""
    }
}
