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
                Log.i("NativeEngine", "✅ trans_engine library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                loadError = "Library not found: ${e.message}"
                Log.e("NativeEngine", "❌ UnsatisfiedLinkError: ${e.message}")
                Log.e("NativeEngine", "Device CPU_ABI: ${android.os.Build.CPU_ABI}")
            } catch (e: Exception) {
                loadError = "Unexpected error: ${e.message}"
                Log.e("NativeEngine", "❌ Exception: ${e.message}")
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
