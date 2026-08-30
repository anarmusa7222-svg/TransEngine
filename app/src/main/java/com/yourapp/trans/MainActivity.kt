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

    private lateinit var tts: TextToSpeech
    private lateinit var modelManager: ModelManager
    private var voskRecognizer: VoskRecognizer? = null
    private var sourceLang = "tr"
    private var targetLang = "en"
    private var isRunning = false
    private var isSpeaking = false
    private val handler = Handler(Looper.getMainLooper())
    private var lastSpeechTime = 0L
    private val silenceChecker = object : Runnable {
        override fun run() {
            if (isRunning && !isSpeaking) {
                if (System.currentTimeMillis() - lastSpeechTime > 1500L) {
                    val t = tvOriginal.text.toString().removePrefix("🗣️ ").removeSuffix("...").trim()
                    if (t.isNotEmpty() && t != "...") translateAndSpeak(t)
                }
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // Check native library before accessing views
        if (!NativeEngine.isNativeLoaded()) {
            val error = NativeEngine.getLoadError() ?: "Unknown error"
            Log.e("MainActivity", "❌ Native library failed to load: $error")
            Toast.makeText(this, "Native library error: $error", Toast.LENGTH_LONG).show()
            
            // Initialize UI to show error
            try {
                tvStatusEmoji = findViewById(R.id.tvStatusEmoji)
                tvStatusText = findViewById(R.id.tvStatusText)
                tvStatusSubtext = findViewById(R.id.tvStatusSubtext)
                tvStatusEmoji.text = "❌"
                tvStatusText.text = "Library Error"
                tvStatusSubtext.text = error
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to show error: ${e.message}")
            }
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
            Log.e("MainActivity", "Layout inflate hatası: ${e.message}")
            e.printStackTrace()
            Toast.makeText(this, "UI Başlatma Hatası: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        
        btnSwapLang.setOnClickListener { swapLanguages() }
        modelManager = ModelManager(this)
        tts = TextToSpeech(this) { s ->
            if (s == TextToSpeech.SUCCESS) {
                tts.language = Locale.ENGLISH
                tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(u: String?) {}
                    override fun onDone(u: String?) { handler.post { isSpeaking = false; if (isRunning) resumeListening() } }
                    override fun onError(u: String?) { handler.post { isSpeaking = false; if (isRunning) resumeListening() } }
                })
                checkModels()
            } else {
                Log.e("MainActivity", "TTS Başlatma Hatası: $s")
                updateStatus("❌", "Ses Hatası", "TTS başlatılamadı")
            }
        }
    }

    private fun checkModels() {
        val needEn = !modelManager.isModelDownloaded("en")
        val needTr = !modelManager.isModelDownloaded("tr")
        if (needEn || needTr) {
            updateStatus("📥", "Modeller indiriliyor", "İlk açılış")
            progressStatus.visibility = View.VISIBLE
            var done = 0; val total = (if (needTr) 1 else 0) + (if (needEn) 1 else 0)
            val onDone = { done++; if (done >= total) runOnUiThread { onReady() } }
            if (needTr) modelManager.downloadModel("tr", { p -> runOnUiThread { tvStatusSubtext.text = "TR: %$p" } }, onDone, { e -> runOnUiThread { updateStatus("❌", "Hata", e) } })
            if (needEn) modelManager.downloadModel("en", { p -> runOnUiThread { tvStatusSubtext.text = "EN: %$p" } }, onDone, { e -> runOnUiThread { updateStatus("❌", "Hata", e) } })
        } else onReady()
    }

    private fun onReady() {
        progressStatus.visibility = View.GONE
        try {
            NativeEngine.initSystem(File(filesDir, "trans.db").absolutePath)
        } catch (e: Exception) {
            Log.e("MainActivity", "Native engine başlatma hatası: ${e.message}")
            updateStatus("❌", "Motor Hatası", e.message ?: "Bilinmeyen hata")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        else startAuto()
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r)
        if (c == 100 && r.isNotEmpty() && r[0] == PackageManager.PERMISSION_GRANTED) startAuto()
    }

    private fun startAuto() {
        if (isRunning) return
        val mp = modelManager.getModelPath(sourceLang)
        if (!File(mp).exists()) { updateStatus("❌", "Model yok", mp); return }
        try {
            voskRecognizer = VoskRecognizer(mp)
            voskRecognizer?.onPartialResult = { t -> runOnUiThread { if (!isSpeaking) { tvOriginal.text = "🗣️ $t..."; lastSpeechTime = System.currentTimeMillis() } } }
            voskRecognizer?.onFinalResult = { t -> runOnUiThread { if (!isSpeaking && t.isNotEmpty()) { tvOriginal.text = "🗣️ $t"; translateAndSpeak(t) } } }
            voskRecognizer?.startListening()
            isRunning = true; lastSpeechTime = System.currentTimeMillis()
            updateStatus("🎙️", "Dinleniyor", "Konuşun")
            handler.post(silenceChecker)
        } catch (e: Exception) {
            Log.e("MainActivity", "Vosk başlatma hatası: ${e.message}")
            updateStatus("❌", "Vosk Hatası", e.message ?: "Bilinmeyen hata")
        }
    }

    private fun translateAndSpeak(text: String) {
        if (text.isBlank()) { resumeListening(); return }
        isSpeaking = true; voskRecognizer?.pauseListening()
        Thread {
            try {
                val json = NativeEngine.translateLive(text, sourceLang, targetLang)
                val tr = """"translated"\s*:\s*"([^"]*)"""".toRegex().find(json)?.groupValues?.get(1) ?: ""
                runOnUiThread {
                    tvTranslated.text = "🌐 $tr"
                    if (tr.isNotEmpty() && tr != text) tts.speak(tr, TextToSpeech.QUEUE_FLUSH, null, "t")
                    else { isSpeaking = false; resumeListening() }
                }
            } catch (e: Exception) { 
                Log.e("MainActivity", "Çeviri hatası: ${e.message}")
                runOnUiThread { isSpeaking = false; resumeListening() } 
            }
        }.start()
    }

    private fun resumeListening() {
        voskRecognizer?.reset(); voskRecognizer?.resumeListening()
        tvOriginal.text = "🗣️ ..."; updateStatus("🎙️", "Dinleniyor", "Konuşun")
    }

    private fun swapLanguages() {
        val t = sourceLang; sourceLang = targetLang; targetLang = t
        tvSourceFlag.text = if (sourceLang == "tr") "🇹🇷" else "🇬🇧"
        tvSourceLang.text = if (sourceLang == "tr") "Türkçe" else "English"
        tvTargetFlag.text = if (targetLang == "tr") "🇹🇷" else "🇬🇧"
        tvTargetLang.text = if (targetLang == "tr") "Türkçe" else "English"
        tts.language = if (targetLang == "en") Locale.ENGLISH else Locale("tr", "TR")
        if (isRunning) { isRunning = false; handler.removeCallbacks(silenceChecker); voskRecognizer?.release(); startAuto() }
    }

    private fun updateStatus(e: String, t: String, s: String) { tvStatusEmoji.text = e; tvStatusText.text = t; tvStatusSubtext.text = s }

    override fun onDestroy() {
        isRunning = false; handler.removeCallbacks(silenceChecker)
        voskRecognizer?.release(); tts.stop(); tts.shutdown()
        super.onDestroy()
    }
}
