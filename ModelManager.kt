package com.yourapp.trans

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.zip.ZipInputStream

class ModelManager(private val context: Context) {
    companion object {
        const val TR_MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-tr-0.3.zip"
        const val EN_MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        const val TR_MODEL_NAME = "vosk-model-small-tr-0.3"
        const val EN_MODEL_NAME = "vosk-model-small-en-us-0.15"
    }

    fun getModelPath(lang: String): String {
        val name = if (lang == "en") EN_MODEL_NAME else TR_MODEL_NAME
        return File(context.filesDir, name).absolutePath
    }

    fun isModelDownloaded(lang: String): Boolean {
        val dir = File(getModelPath(lang))
        return dir.exists() && dir.listFiles()?.isNotEmpty() == true
    }

    fun downloadModel(lang: String, onProgress: (Int) -> Unit, onComplete: () -> Unit, onError: (String) -> Unit) {
        val url = if (lang == "en") EN_MODEL_URL else TR_MODEL_URL
        Thread {
            try {
                val conn = URL(url).openConnection()
                val total = conn.contentLength
                val zis = ZipInputStream(conn.getInputStream())
                var entry = zis.nextEntry
                var dl = 0
                while (entry != null) {
                    val f = File(context.filesDir, entry.name)
                    if (entry.isDirectory) { f.mkdirs() }
                    else {
                        f.parentFile?.mkdirs()
                        val out = FileOutputStream(f)
                        val buf = ByteArray(8192)
                        var len: Int
                        while (zis.read(buf).also { len = it } > 0) {
                            out.write(buf, 0, len); dl += len
                            if (total > 0) onProgress(dl * 100 / total)
                        }
                        out.close()
                    }
                    zis.closeEntry(); entry = zis.nextEntry
                }
                zis.close(); onComplete()
            } catch (e: Exception) { onError(e.message ?: "Hata") }
        }.start()
    }
}
