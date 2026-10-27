package org.truevoicedroid.tts

import android.app.Application
import android.system.Os
import android.util.Log
import java.io.File

/**
 * Installs the Japanese dictionary before native code looks for it.
 *
 * The engine reads jadic.bin from disk, 27 MB of morphological dictionary and
 * connection matrix, and it only looks at `TVTTS_JA_DICT`, beside the loaded
 * module, or beside the executable. An APK's assets are not files, so the
 * asset is copied once into `filesDir` and the variable is pointed at it.
 * Every process creates this Application -- the UI and the `:engine` worker --
 * so the copy is keyed by a temporary name unique to the process and moved
 * into place with a rename, which is atomic; the loser of that race sees the
 * finished file and sets its own variable to it.
 *
 * Without the dictionary the engine still speaks kana, but silently drops
 * every kanji and spells romaji out letter by letter, so a short or partial
 * copy is rejected rather than trusted.
 */
class TruVoiceApp : Application() {
    override fun onCreate() {
        super.onCreate()
        installJapaneseDictionary()
    }

    private fun installJapaneseDictionary() {
        val target = File(filesDir, DICTIONARY)
        val expected = 27_131_038L
        try {
            if (!target.exists() || target.length() != expected) {
                val temp = File(filesDir, "$DICTIONARY.tmp.${android.os.Process.myPid()}")
                assets.open(DICTIONARY).use { input ->
                    temp.outputStream().use { output ->
                        input.copyTo(output, 1 shl 20)
                    }
                }
                if (temp.length() != expected) {
                    Log.e(TAG, "jadic.bin came out ${temp.length()} bytes, expected $expected")
                    temp.delete()
                    return
                }
                if (!temp.exists() || (!temp.renameTo(target) && !(target.exists() && temp.delete()))) {
                    Log.e(TAG, "could not move jadic.bin into place")
                    temp.delete()
                    if (!target.exists()) return
                }
            }
            Os.setenv("TVTTS_JA_DICT", target.absolutePath, true)
        } catch (t: Throwable) {
            Log.e(TAG, "Japanese dictionary unavailable", t)
        }
    }

    private companion object {
        const val TAG = "TruVoiceApp"
        const val DICTIONARY = "jadic.bin"
    }
}
