package kr.carescape.multiverse.voice

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import kr.carescape.multiverse.vehicle.VehicleBus
import java.util.Locale
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInit
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInit

/**
 * Pleos TextToSpeech and SpeechToText. The NPC speaks through the car's speakers and the players answer
 * through the car's microphone. A car without the speech service just stays silent.
 */
object Voice {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var stt: SpeechToText? = null
    private var ttsReady = false
    private var sttReady = false
    private var heard: ((String) -> Unit)? = null
    private var ended: (() -> Unit)? = null

    fun init(context: Context) {
        if (tts != null || stt != null) return
        runCatching { setUp(context.applicationContext) }.onFailure { VehicleBus.note("음성 SDK 사용 불가: ${it.message}") }
    }

    private fun setUp(context: Context) {
        tts = runCatching { TextToSpeech(context, TtsMode.ON_DEVICE) }.getOrNull()
        tts?.let { t ->
            val events = object : EventListener {
                override fun onStart() {}
                override fun onReady() {}
                override fun onDone() {}
                override fun onError(error: TtsError) = main.post { VehicleBus.note("음성 안내 오류: $error") }.let { }
                override fun onStop() {}
                override fun onUpdatedRms(rms: Double) {}
            }
            runCatching {
                t.initialize(object : TtsInit {
                    override fun onSuccess() = main.post {
                        ttsReady = true
                        runCatching { t.addEventListener(events) }
                        runCatching { t.setLocale(Locale.KOREA) }
                        VehicleBus.note("음성 안내 준비됨")
                    }.let { }
                    override fun onFailure(error: TtsError) = main.post { VehicleBus.note("음성 안내 초기화 실패: $error") }.let { }
                })
            }
        }

        stt = runCatching { SpeechToText(context, SttMode.ON_DEVICE) }.getOrNull()
        stt?.let { s ->
            val results = object : ResultListener {
                override fun onUpdated(text: String, isFinal: Boolean) {
                    if (isFinal) main.post { deliver(text) }
                }
                override fun onUpdatedEpdData(start: Long, end: Long) {}
                override fun onStartedRecognition() {}
                override fun onEndedRecognition() = main.post { finish() }.let { }
                override fun onError(error: SttError) = main.post { VehicleBus.note("음성 인식 오류: $error"); finish() }.let { }
                override fun onReady() {}
                override fun onUpdatedRms(rms: Double) {}
            }
            runCatching {
                s.initialize(object : SttInit {
                    override fun onSuccess() = main.post {
                        sttReady = true
                        runCatching { s.addListener(results) }
                        runCatching { s.setLocale(Locale.KOREA) }
                        VehicleBus.note("음성 인식 준비됨")
                    }.let { }
                    override fun onFailure(error: SttError) = main.post { VehicleBus.note("음성 인식 초기화 실패: $error") }.let { }
                })
            }
        }
    }

    val canListen: Boolean get() = stt != null && sttReady

    fun say(text: String) {
        val t = tts ?: return
        if (!ttsReady || text.isBlank()) return
        runCatching { t.speak(text, true) }.onFailure { VehicleBus.note("음성 안내 실패: ${it.message}") }
    }

    fun stopSpeaking() = runCatching { tts?.stop() }.let { }

    /** Listens once. [onText] gets the final sentence, [onEnd] always runs when listening stops. */
    fun listen(onText: (String) -> Unit, onEnd: () -> Unit) {
        val s = stt
        if (s == null || !sttReady) {
            onEnd()
            return
        }
        stopSpeaking()
        heard = onText
        ended = onEnd
        runCatching { s.request() }.onFailure { VehicleBus.note("음성 인식 시작 실패: ${it.message}"); finish() }
    }

    fun stopListening() {
        heard = null
        runCatching { stt?.stop() }
        finish()
    }

    private fun deliver(text: String) {
        VehicleBus.note("들음: $text")
        val cb = heard
        heard = null
        cb?.invoke(text)
    }

    /** The final sentence may arrive just after recognition ends, so [heard] is kept until it is delivered. */
    private fun finish() {
        val cb = ended
        ended = null
        cb?.invoke()
    }
}
