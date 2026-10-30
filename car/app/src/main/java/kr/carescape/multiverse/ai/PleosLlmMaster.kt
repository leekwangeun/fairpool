package kr.carescape.multiverse.ai

import ai.pleos.playground.llm.LLM
import ai.pleos.playground.llm.constant.Mode
import ai.pleos.playground.llm.listener.ResultListener
import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kr.carescape.multiverse.game.CarContext
import kr.carescape.multiverse.game.ChatLine
import kr.carescape.multiverse.game.Episode
import kr.carescape.multiverse.game.Stage
import kr.carescape.multiverse.game.Theme
import kr.carescape.multiverse.vehicle.VehicleBus
import kotlin.coroutines.resume

/**
 * The car's own LLM (Pleos LLM SDK, on-device mode). It writes the story and plays the NPC; the puzzle
 * values still go through [LlmPrompt.merge]. Any failure falls back to [BuiltinMaster] so the game never stalls.
 */
class PleosLlmMaster(context: Context) : GameMaster {
    override val name = "차량 LLM (Pleos)"
    private val llm: LLM? = runCatching { LLM(context.applicationContext, Mode.ON_DEVICE).also { it.initialize() } }
        .onFailure { VehicleBus.note("LLM SDK 사용 불가: ${it.message}") }.getOrNull()

    /**
     * The text of one answer, or null on error or after [timeoutMs]. If the SDK sends the answer in pieces
     * (isFinal false), the pieces are joined; if it never says final, what arrived is used at the timeout.
     */
    private suspend fun ask(prompt: String, timeoutMs: Long): String? {
        val model = llm ?: return null
        val pieces = StringBuilder()
        val answer = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                try {
                    model.generateContent(prompt, object : ResultListener {
                        override fun onResponse(text: String, isFinal: Boolean) {
                            synchronized(pieces) { pieces.append(text) }
                            if (isFinal && cont.isActive) cont.resume(synchronized(pieces) { pieces.toString() })
                        }
                        override fun onError(error: String) {
                            VehicleBus.note("LLM 오류: $error")
                            if (cont.isActive) cont.resume(null)
                        }
                    })
                } catch (e: Exception) {
                    VehicleBus.note("LLM 호출 실패: ${e.message}")
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
        return answer ?: synchronized(pieces) { pieces.toString() }.ifBlank { null }
    }

    override suspend fun episode(theme: Theme, ctx: CarContext, seed: Long): Episode {
        val base = BuiltinMaster.episode(theme, ctx, seed)
        val answer = ask(LlmPrompt.episodePrompt(base, ctx), 25_000)
        val merged = answer?.let { LlmPrompt.merge(base, it) }
        VehicleBus.note(if (merged != null) "LLM이 에피소드를 썼어요: ${merged.title}" else "LLM 응답 없음, 내장 생성기 사용")
        return merged ?: base
    }

    override suspend fun reply(episode: Episode, stage: Stage, ctx: CarContext, history: List<ChatLine>, question: String): String {
        val answer = ask(LlmPrompt.replyPrompt(episode, stage, ctx, history, question), 12_000)
            ?.let { LlmPrompt.cleanReply(it, episode.code) }
        return if (answer.isNullOrBlank()) BuiltinMaster.reply(episode, stage, ctx, history, question) else answer
    }

    fun release() = runCatching { llm?.release() }
}
