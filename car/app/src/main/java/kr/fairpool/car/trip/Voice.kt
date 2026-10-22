package kr.fairpool.car.trip

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.Locale
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInit
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInit

/**
 * Pleos TextToSpeech and SpeechToText. FairPool only speaks when the driver is looking at a stopped-car
 * screen (the L5 card, the L6 result) and only listens for two answers on the L5 card.
 */
object Voice {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var stt: SpeechToText? = null
    private var ttsReady = false
    private var sttReady = false
    private var answer: ((Boolean) -> Unit)? = null
    private var afterSpeech: (() -> Unit)? = null

    /** Never takes the app down: a car without the speech service (or permission) just stays silent. */
    fun init(context: Context) {
        if (tts != null) return
        runCatching { setUp(context) }.onFailure {
            TripEngine.log("음성 SDK 사용 불가: ${it.message}")
            tts = null
            stt = null
        }
    }

    private fun setUp(context: Context) {
        tts = runCatching { TextToSpeech(context.applicationContext, TtsMode.HYBRID) }
            .onFailure { TripEngine.log("음성 안내 SDK 없음: ${it.message}") }.getOrNull()
        tts?.let { t ->
            // the SDK only accepts listeners once initialize() has set it up
            val events = object : EventListener {
                override fun onStart() {}
                override fun onReady() {}
                override fun onDone() = main.post { afterSpeech?.invoke(); afterSpeech = null }.let { }
                override fun onError(error: TtsError) = main.post { TripEngine.log("음성 안내 오류: $error"); afterSpeech?.invoke(); afterSpeech = null }.let { }
                override fun onStop() {}
                override fun onUpdatedRms(rms: Double) {}
            }
            runCatching {
                t.initialize(object : TtsInit {
                    override fun onSuccess() = main.post {
                        ttsReady = true
                        runCatching { t.addEventListener(events) }.onFailure { TripEngine.log("음성 안내 리스너 실패: ${it.message}") }
                        runCatching { t.setLocale(Locale.KOREA) }
                        TripEngine.log("음성 안내 준비됨")
                    }.let { }
                    override fun onFailure(error: TtsError) = main.post { TripEngine.log("음성 안내 초기화 실패: $error") }.let { }
                })
            }.onFailure { TripEngine.log("음성 안내 초기화 실패: ${it.message}") }
        }

        stt = runCatching { SpeechToText(context.applicationContext, SttMode.HYBRID) }
            .onFailure { TripEngine.log("음성 인식 SDK 없음: ${it.message}") }.getOrNull()
        stt?.let { s ->
            val results = object : ResultListener {
                override fun onUpdated(text: String, isFinal: Boolean) {
                    if (isFinal) main.post { heard(text) }
                }
                override fun onUpdatedEpdData(start: Long, end: Long) {}
                override fun onStartedRecognition() {}
                override fun onEndedRecognition() {}
                override fun onError(error: SttError) = main.post { TripEngine.log("음성 인식 오류: $error") }.let { }
                override fun onReady() {}
                override fun onUpdatedRms(rms: Double) {}
            }
            runCatching {
                s.initialize(object : SttInit {
                    override fun onSuccess() = main.post {
                        sttReady = true
                        runCatching { s.addListener(results) }.onFailure { TripEngine.log("음성 인식 리스너 실패: ${it.message}") }
                        runCatching { s.setLocale(Locale.KOREA) }
                        TripEngine.log("음성 인식 준비됨")
                    }.let { }
                    override fun onFailure(error: SttError) = main.post { TripEngine.log("음성 인식 초기화 실패: $error") }.let { }
                })
            }.onFailure { TripEngine.log("음성 인식 초기화 실패: ${it.message}") }
        }
    }

    /** Reads [text] once; [then] runs when it has been read (or could not be). */
    fun say(text: String, then: (() -> Unit)? = null) {
        val t = tts
        if (t == null || !ttsReady) {
            then?.invoke()
            return
        }
        afterSpeech = then
        runCatching { t.speak(text, true) }.onFailure {
            TripEngine.log("음성 안내 실패: ${it.message}")
            afterSpeech = null
            then?.invoke()
        }
    }

    /** L5: listens once for "맞아" (true) or "잘못 인식" (false). */
    fun askConfirm(onAnswer: (Boolean) -> Unit) {
        val s = stt ?: return
        if (!sttReady) return
        answer = onAnswer
        runCatching { s.request() }.onFailure { TripEngine.log("음성 인식 시작 실패: ${it.message}"); answer = null }
    }

    fun stopListening() {
        answer = null
        runCatching { stt?.stop() }
    }

    private fun heard(text: String) {
        val t = text.replace(" ", "")
        TripEngine.log("음성 인식: $text")
        val cb = answer ?: return
        when {
            t.contains("잘못") || t.contains("아니") -> { answer = null; cb(false) }
            t.contains("맞아") || t.contains("맞습니다") || t.contains("응") || t.contains("네") -> { answer = null; cb(true) }
        }
    }
}
