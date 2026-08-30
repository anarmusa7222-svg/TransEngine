package com.yourapp.trans

import android.util.Log

class NativeEngine {
    companion object {
        init {
            try {
                System.loadLibrary("trans_engine")
                Log.i("NativeEngine", "✅ trans_engine kütüphanesi başarıyla yüklendi")
            } catch (e: UnsatisfiedLinkError) {
                Log.e("NativeEngine", "❌ trans_engine kütüphanesi yüklenemedi: ${e.message}")
                throw RuntimeException("Native library loading failed", e)
            } catch (e: Exception) {
                Log.e("NativeEngine", "❌ Beklenmeyen hata: ${e.message}")
                throw RuntimeException("Unexpected error loading native library", e)
            }
        }

        @JvmStatic
        external fun initSystem(dbPath: String): Boolean

        @JvmStatic
        external fun translateLive(input: String, sourceLang: String, targetLang: String): String
    }
}
