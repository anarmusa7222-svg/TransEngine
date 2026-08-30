package com.yourapp.trans

class NativeEngine {
    companion object {
        init { System.loadLibrary("trans_engine") }
        @JvmStatic external fun initSystem(dbPath: String): Boolean
        @JvmStatic external fun translateLive(input: String, sourceLang: String, targetLang: String): String
    }
}
