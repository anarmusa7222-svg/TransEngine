package com.yourapp.trans

import android.util.Log

class NativeEngine {
    companion object {
        private var isLoaded = false
        private var loadError: String? = null

        init {
            try {
                System.loadLibrary("trans_engine")
                isLoaded = true
                Log.i("NativeEngine", "✅ trans_engine kütüphanesi başarıyla yüklendi")
            } catch (e: UnsatisfiedLinkError) {
                loadError = "Native library not found: ${e.message}"
                Log.e("NativeEngine", "❌ trans_engine kütüphanesi yüklenemedi: ${e.message}")
                Log.e("NativeEngine", "Cihaz bilgisi - CPU_ABI: ${android.os.Build.CPU_ABI}, CPU_ABI2: ${android.os.Build.CPU_ABI2}")
            } catch (e: Exception) {
                loadError = "Unexpected error: ${e.message}"
                Log.e("NativeEngine", "❌ Beklenmeyen hata: ${e.message}")
            }
        }

        fun isNativeLoaded(): Boolean = isLoaded
        fun getLoadError(): String? = loadError

        @JvmStatic
        external fun initSystem(dbPath: String): Boolean

        @JvmStatic
        external fun translateLive(input: String, sourceLang: String, targetLang: String): String
    }
}
