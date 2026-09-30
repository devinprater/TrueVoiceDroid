package org.truevoicedroid.tts

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

/**
 * Minimal accessible test UI for the TrueVoiceDroid engine: text field,
 * voice spinner (Peter default), rate/pitch sliders, Speak/Stop, fallback
 * toggles, and a status line. Speaks through the TTS framework pointed at
 * this app's own service, so the service path — including the fallback —
 * is what gets exercised.
 */
class MainActivity : AppCompatActivity() {

    private var tts: TextToSpeech? = null
    private var voices: List<Voice> = emptyList()

    private lateinit var inputText: EditText
    private lateinit var spinnerVoice: Spinner
    private lateinit var seekRate: SeekBar
    private lateinit var seekPitch: SeekBar
    private lateinit var switchFallback: Switch
    private lateinit var inputFallbackPackage: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        inputText = findViewById(R.id.input_text)
        spinnerVoice = findViewById(R.id.spinner_voice)
        seekRate = findViewById(R.id.seek_rate)
        seekPitch = findViewById(R.id.seek_pitch)
        switchFallback = findViewById(R.id.switch_fallback)
        inputFallbackPackage = findViewById(R.id.input_fallback_package)
        status = findViewById(R.id.status)
        val labelRate: TextView = findViewById(R.id.label_rate)
        val labelPitch: TextView = findViewById(R.id.label_pitch)
        val buttonSpeak: Button = findViewById(R.id.button_speak)
        val buttonStop: Button = findViewById(R.id.button_stop)

        if (inputText.text.isEmpty()) {
            inputText.setText("Hello world. This is TruVoice speaking.")
        }
        seekRate.progress = 50 // 50..200 %, 100 % neutral
        seekPitch.progress = 50
        updateRateLabel(labelRate)
        updatePitchLabel(labelPitch)
        seekRate.setOnSeekBarChangeListener(simpleListener { updateRateLabel(labelRate) })
        seekPitch.setOnSeekBarChangeListener(simpleListener { updatePitchLabel(labelPitch) })

        switchFallback.isChecked = GoogleTtsFallback.isEnabled(this)
        inputFallbackPackage.setText(GoogleTtsFallback.fallbackPackage(this))
        switchFallback.setOnCheckedChangeListener { _, checked ->
            GoogleTtsFallback.setEnabled(this, checked)
            setStatus(if (checked) "Fallback on" else "Fallback off")
        }
        val switchOverrideVoice = findViewById<Switch>(R.id.switch_override_voice)
        val switchShortenPauses = findViewById<Switch>(R.id.switch_shorten_pauses)
        val voicePrefs = getSharedPreferences(TruVoiceTtsService.PREFS, MODE_PRIVATE)
        switchOverrideVoice.isChecked =
            voicePrefs.getBoolean(TruVoiceTtsService.KEY_OVERRIDE_VOICE, true)
        switchShortenPauses.isChecked =
            voicePrefs.getBoolean(TruVoiceTtsService.KEY_SHORTEN_PAUSES, true)
        switchOverrideVoice.setOnCheckedChangeListener { _, checked ->
            voicePrefs.edit().putBoolean(TruVoiceTtsService.KEY_OVERRIDE_VOICE, checked).apply()
        }
        switchShortenPauses.setOnCheckedChangeListener { _, checked ->
            voicePrefs.edit().putBoolean(TruVoiceTtsService.KEY_SHORTEN_PAUSES, checked).apply()
        }
        val switchClassicRate = findViewById<Switch>(R.id.switch_classic_rate)
        switchClassicRate.isChecked =
            voicePrefs.getInt(TruVoiceTtsService.KEY_SAMPLE_RATE_HZ, 16000) == 11025
        switchClassicRate.setOnCheckedChangeListener { _, checked ->
            voicePrefs.edit().putInt(
                TruVoiceTtsService.KEY_SAMPLE_RATE_HZ, if (checked) 11025 else 16000
            ).apply()
            // Takes effect on the next utterance; the service re-reads it
            // per request and switches the synths between utterances.
            setStatus(if (checked) "Classic 11 kHz on" else "16 kHz wideband on")
        }
        inputFallbackPackage.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                GoogleTtsFallback.setFallbackPackage(this, inputFallbackPackage.text.toString())
                inputFallbackPackage.setText(GoogleTtsFallback.fallbackPackage(this))
            }
        }

        // Headless test hook (debug builds only): launch with
        // `--es autospeak "..."` (+ optional `--ei voice N`, `--es engine
        // <package>|default`) to speak through the framework TTS path
        // without touching the screen.
        val engineExtra = intent.getStringExtra(EXTRA_ENGINE)
        val engineArg = when {
            !BuildConfig.DEBUG -> ENGINE_PACKAGE
            engineExtra == "default" -> null
            engineExtra != null -> engineExtra
            else -> ENGINE_PACKAGE
        }
        tts = TextToSpeech(this, { code ->
            if (code == TextToSpeech.SUCCESS) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String) {
                        runOnUiThread { setStatus("Speaking…") }
                    }

                    override fun onDone(utteranceId: String) {
                        runOnUiThread { setStatus("Done") }
                    }

                    @Deprecated("deprecated")
                    override fun onError(utteranceId: String) {
                        runOnUiThread { setStatus("Error speaking") }
                    }

                    override fun onError(utteranceId: String, errorCode: Int) {
                        runOnUiThread { setStatus("Error speaking ($errorCode)") }
                    }

                    override fun onRangeStart(
                        utteranceId: String,
                        start: Int,
                        end: Int,
                        frame: Int
                    ) {
                    }
                })
                loadVoices()
            } else {
                setStatus("Engine init failed ($code)")
            }
            android.util.Log.i(
                TAG,
                "tts init code=$code defaultEngine=${tts?.defaultEngine} " +
                    "engines=${tts?.engines?.joinToString { it.name }}"
            )
            // Direct visibility probe: what does OUR process resolve?
            try {
                val probe = android.content.Intent("android.intent.action.TTS_SERVICE")
                val resolved = packageManager.queryIntentServices(probe, 0)
                android.util.Log.i(
                    TAG,
                    "pm probe: ${resolved.size} services: " +
                        resolved.joinToString { it.serviceInfo.packageName }
                )
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "pm probe failed", t)
            }
        }, engineArg)
        @Suppress("DEPRECATION")
        switchFallback.setTextOn("Google TTS fallback")
        switchFallback.setTextOff("Google TTS fallback")

        buttonSpeak.setOnClickListener { speak() }
        buttonStop.setOnClickListener {
            tts?.stop()
            setStatus("Stopped")
        }
    }

    private fun loadVoices() {
        val all = try {
            tts?.voices?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        // Keep only this engine's voices, in engine order (0-19).
        voices = all.filter { it.name.startsWith("TruVoice ") }.sortedBy {
            VoiceCatalog.indexFromAndroidName(it.name) ?: Int.MAX_VALUE
        }
        val display = if (voices.isEmpty()) {
            VoiceCatalog.all.map { "${it.name} (${it.language})" }
        } else {
            voices.map { voice ->
                val idx = VoiceCatalog.indexFromAndroidName(voice.name)
                val info = idx?.let { VoiceCatalog.infoFor(it) }
                if (info != null) "${info.name} (${info.language})" else voice.name
            }
        }
        spinnerVoice.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, display
        )
        // Start on the saved TalkBack voice when valid, else Peter.
        val peter = if (voices.isEmpty()) 0 else voices.indexOfFirst {
            VoiceCatalog.indexFromAndroidName(it.name) == VoiceCatalog.DEFAULT_VOICE
        }.takeIf { it >= 0 } ?: 0
        val savedIdx = try {
            getSharedPreferences(
                TruVoiceTtsService.PREFS, MODE_PRIVATE
            ).getInt(
                TruVoiceTtsService.KEY_DEFAULT_VOICE, VoiceCatalog.DEFAULT_VOICE
            )
        } catch (t: Throwable) {
            VoiceCatalog.DEFAULT_VOICE
        }
        val start = if (voices.isEmpty()) {
            if (savedIdx in 0..19) savedIdx else peter
        } else {
            voices.indexOfFirst {
                VoiceCatalog.indexFromAndroidName(it.name) == savedIdx
            }.takeIf { it >= 0 } ?: peter
        }
        spinnerVoice.setSelection(start)
        setStatus(if (voices.isEmpty()) "Engine not bound yet" else "Ready")
        // Selecting a voice saves it immediately: that is the voice
        // TalkBack uses, no need to press Speak first.
        spinnerVoice.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: android.view.View?, pos: Int, id: Long
            ) {
                saveSpinnerVoice(pos)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        // Headless test hook (debug builds only): launch with
        // `--es autospeak "..."` (+ optional `--ei voice N`) to speak
        // through the framework TTS path without touching the screen.
        if (BuildConfig.DEBUG && intent.hasExtra(EXTRA_AUTOSPEAK)) {
            val text = intent.getStringExtra(EXTRA_AUTOSPEAK) ?: return
            val voiceIdx = intent.getIntExtra(EXTRA_VOICE, -1)
            if (voiceIdx >= 0 && voices.isNotEmpty()) {
                val at = voices.indexOfFirst {
                    VoiceCatalog.indexFromAndroidName(it.name) == voiceIdx
                }
                if (at >= 0) spinnerVoice.setSelection(at)
            }
            inputText.setText(text)
            intent.getIntExtra(EXTRA_RATE, -1).takeIf { it >= 50 }?.let {
                seekRate.progress = (it - 50).coerceIn(0, seekRate.max)
            }
            intent.getIntExtra(EXTRA_PITCH, -1).takeIf { it >= 50 }?.let {
                seekPitch.progress = (it - 50).coerceIn(0, seekPitch.max)
            }
            intent.getIntExtra(EXTRA_SAMPLE_RATE, -1).takeIf { it == 11025 || it == 16000 }?.let {
                getSharedPreferences(TruVoiceTtsService.PREFS, MODE_PRIVATE).edit()
                    .putInt(TruVoiceTtsService.KEY_SAMPLE_RATE_HZ, it).apply()
            }
            android.util.Log.i(TAG, "autospeak: voice=$voiceIdx text=$text")
            speak()
        }
    }

    private fun speak() {
        val text = inputText.text.toString()
        if (text.isBlank()) {
            setStatus("Nothing to speak")
            return
        }
        GoogleTtsFallback.setFallbackPackage(this, inputFallbackPackage.text.toString())
        val engine = tts ?: run {
            setStatus("Engine not ready")
            return
        }
        val pos = spinnerVoice.selectedItemPosition
        // Debug headless check: --ez novoice true skips setVoice, so the
        // request carries only a language, exactly like TalkBack sends.
        if (!intent.getBooleanExtra(EXTRA_NO_VOICE, false) &&
            voices.isNotEmpty() && pos in voices.indices
        ) {
            val voice = voices[pos]
            try {
                engine.voice = voice
            } catch (e: Exception) {
                setStatus("Voice rejected")
                return
            }
            try {
                engine.language = voice.locale
            } catch (e: Exception) {
            }
        } else {
            try {
                engine.language = Locale.US
            } catch (e: Exception) {
            }
        }
        engine.setSpeechRate((seekRate.progress + 50) / 100.0f)
        engine.setPitch((seekPitch.progress + 50) / 100.0f)
        val code = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "truvoice-ui")
        android.util.Log.i(TAG, "speak submitted code=$code voice=${engine.voice?.name}")
        if (code != TextToSpeech.SUCCESS) setStatus("Speak rejected ($code)")
    }

    private fun updateRateLabel(label: TextView) {
        label.text = "Rate: ${seekRate.progress + 50}%"
    }

    /**
     * Saves the spinner position as the TalkBack voice immediately, so
     * merely selecting a voice switches TalkBack without pressing Speak.
     */
    private fun saveSpinnerVoice(pos: Int) {
        val index = if (voices.isNotEmpty() && pos in voices.indices) {
            VoiceCatalog.indexFromAndroidName(voices[pos].name)
        } else if (pos in 0..19) {
            pos
        } else {
            null
        }
        if (index == null) return
        getSharedPreferences(TruVoiceTtsService.PREFS, MODE_PRIVATE).edit()
            .putInt(TruVoiceTtsService.KEY_DEFAULT_VOICE, index).apply()
        val name = VoiceCatalog.infoFor(index)?.name ?: index.toString()
        android.util.Log.i(TAG, "voice selected: $name")
        setStatus("Voice saved: $name — TalkBack will use it")
    }

    private fun updatePitchLabel(label: TextView) {
        label.text = "Pitch: ${seekPitch.progress + 50}%"
    }

    private fun setStatus(message: String) {
        status.text = message
    }

    private fun simpleListener(onChange: () -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = onChange()
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }

    override fun onDestroy() {
        try {
            tts?.shutdown()
        } catch (e: Exception) {
        }
        tts = null
        super.onDestroy()
    }

    companion object {
        private const val ENGINE_PACKAGE = "org.truevoicedroid.tts"
        private const val TAG = "TruVoiceUi"

        /** Debug-only launch extras for headless verification. */
        const val EXTRA_AUTOSPEAK = "autospeak"
        const val EXTRA_VOICE = "voice"
        const val EXTRA_ENGINE = "engine"
        const val EXTRA_NO_VOICE = "novoice"
        const val EXTRA_RATE = "rate"
        const val EXTRA_PITCH = "pitch"
        const val EXTRA_SAMPLE_RATE = "sr"
    }
}
