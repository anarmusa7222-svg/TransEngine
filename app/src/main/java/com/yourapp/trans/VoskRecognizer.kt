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
            Log.i("VoskRecognizer", "Initializing Vosk with model: $modelPath")
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            
            model = Model(modelPath)
            if (model == null) {
                throw RuntimeException("Failed to create Model object")
            }
            
            recognizer = Recognizer(model, sampleRate)
            if (recognizer == null) {
                throw RuntimeException("Failed to create Recognizer object")
            }
            
            Log.i("VoskRecognizer", "✅ Vosk initialized successfully")
        } catch (e: Exception) {
            Log.e("VoskRecognizer", "❌ Initialization error: ${e.message}")
            e.printStackTrace()
            model = null
            recognizer = null
            onError?.invoke(e.message ?: "Failed to initialize Vosk")
        }
    }

    fun startListening() {
        if (isListening) {
            Log.w("VoskRecognizer", "Already listening")
            return
        }
        
        if (model == null) {
            Log.e("VoskRecognizer", "Model is null")
            onError?.invoke("Model not loaded")
            return
        }
        
        if (recognizer == null) {
            Log.e("VoskRecognizer", "Recognizer is null")
            onError?.invoke("Recognizer not initialized")
            return
        }
        
        try {
            Log.i("VoskRecognizer", "Starting listening...")
            val bufSize = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (bufSize <= 0) {
                throw RuntimeException("Invalid buffer size: $bufSize")
            }
            
            audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize * 2)
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                throw RuntimeException("AudioRecord failed to initialize")
            }
            
            audioRecord?.startRecording()
            isListening = true
            
            listeningThread = Thread {
                try {
                    val buf = ShortArray(bufSize)
                    while (isListening) {
                        try {
                            if (isPaused) {
                                Thread.sleep(100)
                                continue
                            }
                            
                            val read = audioRecord?.read(buf, 0, buf.size) ?: 0
                            if (read > 0 && recognizer != null) {
                                if (recognizer!!.acceptWaveForm(buf, read)) {
                                    val result = recognizer?.result ?: ""
                                    val text = extractText(result)
                                    if (text.isNotEmpty()) {
                                        onFinalResult?.invoke(text)
                                    }
                                } else {
                                    val partial = recognizer?.partialResult ?: ""
                                    val text = extractText(partial)
                                    if (text.isNotEmpty()) {
                                        onPartialResult?.invoke(text)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("VoskRecognizer", "Error in listening loop: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("VoskRecognizer", "Listening thread error: ${e.message}")
                }
            }
            listeningThread?.start()
            Log.i("VoskRecognizer", "✅ Listening started")
        } catch (e: Exception) {
            Log.e("VoskRecognizer", "Failed to start listening: ${e.message}")
            e.printStackTrace()
            isListening = false
            onError?.invoke(e.message ?: "Failed to start listening")
        }
    }

    fun pauseListening() {
        isPaused = true
        Log.i("VoskRecognizer", "Paused")
    }

    fun resumeListening() {
        isPaused = false
        Log.i("VoskRecognizer", "Resumed")
    }

    fun stopListening() {
        try {
            isListening = false
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            Log.i("VoskRecognizer", "Stopped")
        } catch (e: Exception) {
            Log.e("VoskRecognizer", "Error stopping: ${e.message}")
        }
    }

    fun reset() {
        try {
            recognizer?.reset()
            Log.i("VoskRecognizer", "Reset")
        } catch (e: Exception) {
            Log.e("VoskRecognizer", "Error resetting: ${e.message}")
        }
    }

    fun release() {
        try {
            stopListening()
            listeningThread?.join(1000)
            recognizer?.close()
            model?.close()
            recognizer = null
            model = null
            Log.i("VoskRecognizer", "✅ Released")
        } catch (e: Exception) {
            Log.e("VoskRecognizer", "Error releasing: ${e.message}")
        }
    }

    private fun extractText(json: String): String {
        return try {
            \"\"\"\"(?:text|partial)\"\\s*:\\s*\"([^\"]*)\"\"\"\".toRegex().find(json)?.groupValues?.get(1)?.trim() ?: ""
        } catch (e: Exception) {
            Log.e("VoskRecognizer", "Error extracting text: ${e.message}")
            ""
        }
    }
}
