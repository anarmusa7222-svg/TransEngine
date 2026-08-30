package com.yourapp.trans

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var tvStatusEmoji: TextView
    private lateinit var tvStatusText: TextView
    private lateinit var tvStatusSubtext: TextView
    private lateinit var progressStatus: CircularProgressIndicator
    private lateinit var tvSourceFlag: TextView
    private lateinit var tvSourceLang: TextView
    private lateinit var tvTargetFlag: TextView
    private lateinit var tvTargetLang: TextView
    private lateinit var btnSwapLang: MaterialButton
    private lateinit var tvOriginal: TextView
    private lateinit var tvTranslated: TextView

    private var tts: TextToSpeech? = null
    private var modelManager: ModelManager? = null
    private var voskRecognizer: VoskRecognizer? = null
    private var sourceLang = "tr"
    private var targetLang = "en"
    private var isRunning = false
    private var isSpeaking = false
    private val handler = Handler(Looper.getMainLooper())
    private var lastSpeechTime = 0L
    private val silenceChecker = object : Runnable {
        override fun run() {
            try {
                if (isRunning && !isSpeaking) {
                    if (System.currentTimeMillis() - lastSpeechTime > 1500L) {
                        val t = tvOriginal.text.toString().removePrefix("🗣️ ").removeSuffix("...").trim()
                        if (t.isNotEmpty() && t != "...") translateAndSpeak(t)
                    }
                }
                handler.postDelayed(this, 500)
            } catch (e: Exception) {
                Log.e("MainActivity", "silenceChecker error: ${e.message}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // Check native library first
        if (!NativeEngine.isNativeLoaded()) {
            val error = NativeEngine.getLoadError() ?: "Unknown error"
            Log.e("MainActivity", "❌ Native library failed: $error")
            showError("Library Error", error)
            return
        }
        
        try {
            tvStatusEmoji = findViewById(R.id.tvStatusEmoji)
            tvStatusText = findViewById(R.id.tvStatusText)
            tvStatusSubtext = findViewById(R.id.tvStatusSubtext)
            progressStatus = findViewById(R.id.progressStatus)
            tvSourceFlag = findViewById(R.id.tvSourceFlag)
            tvSourceLang = findViewById(R.id.tvSourceLang)
            tvTargetFlag = findViewById(R.id.tvTargetFlag)
            tvTargetLang = findViewById(R.id.tvTargetLang)
            btnSwapLang = findViewById(R.id.btnSwapLang)
            tvOriginal = findViewById(R.id.tvOriginal)
            tvTranslated = findViewById(R.id.tvTranslated)
        } catch (e: Exception) {
            Log.e("MainActivity", "Layout error: ${e.message}")
            showError("UI Error", e.message ?: "Failed to load UI")
            return
        }
        
        try {
            btnSwapLang.setOnClickListener { swapLanguages() }
            modelManager = ModelManager(this)
            
            tts = TextToSpeech(this) { status ->
                try {
                    if (status == TextToSpeech.SUCCESS) {
                        tts?.language = Locale.ENGLISH
                        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                            override fun onStart(u: String?) {}
                            override fun onDone(u: String?) {
                                handler.post { 
                                    try {
                                        isSpeaking = false
                                        if (isRunning) resumeListening()
                                    } catch (e: Exception) {
                                        Log.e("MainActivity", "onDone error: ${e.message}")
                                    }
                                }
                            }
                            override fun onError(u: String?) {
                                handler.post { 
                                    try {
                                        isSpeaking = false
                                        if (isRunning) resumeListening()
                                    } catch (e: Exception) {
                                        Log.e("MainActivity", "onError error: ${e.message}")
                                    }
                                }
                            }
                        })
                        checkModels()
                    } else {
                        Log.e("MainActivity", "TTS initialization failed: $status")
                        updateStatus("❌", "TTS Error", "Failed to initialize")
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "TTS callback error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "onCreate error: ${e.message}")
            showError("Init Error", e.message ?: "Unknown error")
        }
    }

    private fun showError(title: String, message: String) {
        try {
            tvStatusEmoji.text = "❌"
            tvStatusText.text = title
            tvStatusSubtext.text = message
            Toast.makeText(this, "$title: $message", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "$title: $message", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkModels() {
        try {
            val needEn = modelManager?.isModelDownloaded("en") == false
            val needTr = modelManager?.isModelDownloaded("tr") == false
            if (needEn || needTr) {
                updateStatus("📥", "Downloading models", "First launch")
                progressStatus.visibility = View.VISIBLE
                var done = 0
                val total = (if (needTr) 1 else 0) + (if (needEn) 1 else 0)
                val onDone = { 
                    done++
                    if (done >= total) {
                        runOnUiThread { onReady() }
                    }
                }
                if (needTr) {
                    modelManager?.downloadModel("tr", 
                        { p -> runOnUiThread { tvStatusSubtext.text = "TR: $p%" } },
                        onDone,
                        { e -> runOnUiThread { updateStatus("❌", "Error", e) } }
                    )
                }
                if (needEn) {
                    modelManager?.downloadModel("en",
                        { p -> runOnUiThread { tvStatusSubtext.text = "EN: $p%" } },
                        onDone,
                        { e -> runOnUiThread { updateStatus("❌", "Error", e) } }
                    )
                }
            } else {
                onReady()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "checkModels error: ${e.message}")
            updateStatus("❌", "Model Error", e.message ?: "Unknown error")
        }
    }

    private fun onReady() {
        try {
            progressStatus.visibility = View.GONE
            val dbPath = File(filesDir, "trans.db").absolutePath
            Log.i("MainActivity", "Initializing native engine at: $dbPath")
            NativeEngine.initSystem(dbPath)
            
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
            } else {
                startAuto()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "onReady error: ${e.message}")
            updateStatus("❌", "Engine Error", e.message ?: "Failed to initialize")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        try {
            if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startAuto()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Permission error: ${e.message}")
        }
    }

    private fun startAuto() {
        try {
            if (isRunning) return
            
            val modelManager = modelManager
            if (modelManager == null) {
                updateStatus("❌", "Error", "Model manager not initialized")
                return
            }
            
            val mp = modelManager.getModelPath(sourceLang)
            if (!File(mp).exists()) {
                Log.e("MainActivity", "Model not found: $mp")
                updateStatus("❌", "Missing Model", "Path: $mp")
                return
            }
            
            Log.i("MainActivity", "Creating VoskRecognizer with model: $mp")
            
            val recognizer = try {
                VoskRecognizer(mp)
            } catch (e: Exception) {
                Log.e("MainActivity", "❌ Failed to create VoskRecognizer: ${e.message}")
                e.printStackTrace()
                updateStatus("❌", "Vosk Error", e.message ?: "Failed to load model")
                return
            }
            
            // Set callbacks before starting
            recognizer.onPartialResult = { t ->
                try {
                    runOnUiThread {
                        if (!isSpeaking && t.isNotEmpty()) {
                            tvOriginal.text = "🗣️ $t..."
                            lastSpeechTime = System.currentTimeMillis()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "onPartialResult error: ${e.message}")
                }
            }
            
            recognizer.onFinalResult = { t ->
                try {
                    runOnUiThread {
                        if (!isSpeaking && t.isNotEmpty()) {
                            tvOriginal.text = "🗣️ $t"
                            translateAndSpeak(t)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "onFinalResult error: ${e.message}")
                }
            }
            
            recognizer.onError = { error ->
                Log.e("MainActivity", "VoskRecognizer error: $error")
                runOnUiThread {
                    updateStatus("❌", "Listening Error", error)
                }
            }
            
            try {
                recognizer.startListening()
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to start listening: ${e.message}")
                e.printStackTrace()
                updateStatus("❌", "Listen Error", e.message ?: "Failed to start")
                return
            }
            
            voskRecognizer = recognizer
            isRunning = true
            lastSpeechTime = System.currentTimeMillis()
            updateStatus("🎙️", "Listening", "Speak now")
            handler.post(silenceChecker)
            
            Log.i("MainActivity", "✅ Listening started successfully")
        } catch (e: Exception) {
            Log.e("MainActivity", "❌ startAuto exception: ${e.message}")
            e.printStackTrace()
            updateStatus("❌", "Start Error", e.message ?: "Failed to start listening")
        }
    }

    private fun translateAndSpeak(text: String) {
        if (text.isBlank()) {
            resumeListening()
            return
        }
        
        isSpeaking = true
        voskRecognizer?.pauseListening()
        
        Thread {
            try {
                val json = NativeEngine.translateLive(text, sourceLang, targetLang)
                Log.i("MainActivity", "Translation response: $json")
                val tr = \"\"\"\"translated\"\\s*:\\s*\"([^\"]*)\"\"\"\".toRegex().find(json)?.groupValues?.get(1) ?: ""
                
                runOnUiThread {
                    try {
                        tvTranslated.text = "🌐 $tr"
                        if (tr.isNotEmpty() && tr != text) {
                            tts?.speak(tr, TextToSpeech.QUEUE_FLUSH, null, "t")
                        } else {
                            isSpeaking = false
                            resumeListening()
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "UI update error: ${e.message}")
                        isSpeaking = false
                        resumeListening()
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Translation error: ${e.message}")
                e.printStackTrace()
                runOnUiThread {
                    isSpeaking = false
                    resumeListening()
                }
            }
        }.start()
    }

    private fun resumeListening() {
        try {
            voskRecognizer?.reset()
            voskRecognizer?.resumeListening()
            tvOriginal.text = "🗣️ ..."
            updateStatus("🎙️", "Listening", "Speak now")
        } catch (e: Exception) {
            Log.e("MainActivity", "resumeListening error: ${e.message}")
        }
    }

    private fun swapLanguages() {
        try {
            val t = sourceLang
            sourceLang = targetLang
            targetLang = t
            tvSourceFlag.text = if (sourceLang == "tr") "🇹🇷" else "🇬🇧"
            tvSourceLang.text = if (sourceLang == "tr") "Türkçe" else "English"
            tvTargetFlag.text = if (targetLang == "tr") "🇹🇷" else "🇬🇧"
            tvTargetLang.text = if (targetLang == "tr") "Türkçe" else "English"
            tts?.language = if (targetLang == "en") Locale.ENGLISH else Locale("tr", "TR")
            
            if (isRunning) {
                isRunning = false
                handler.removeCallbacks(silenceChecker)
                voskRecognizer?.release()
                voskRecognizer = null
                startAuto()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "swapLanguages error: ${e.message}")
        }
    }

    private fun updateStatus(emoji: String, title: String, subtitle: String) {
        try {
            tvStatusEmoji.text = emoji
            tvStatusText.text = title
            tvStatusSubtext.text = subtitle
        } catch (e: Exception) {
            Log.e("MainActivity", "updateStatus error: ${e.message}")
        }
    }

    override fun onDestroy() {
        try {
            isRunning = false
            handler.removeCallbacks(silenceChecker)
            voskRecognizer?.release()
            voskRecognizer = null
            tts?.stop()
            tts?.shutdown()
            tts = null
        } catch (e: Exception) {
            Log.e("MainActivity", "onDestroy error: ${e.message}")
        }
        super.onDestroy()
    }
}
