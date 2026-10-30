package kr.carescape.multiverse.ai

import kr.carescape.multiverse.game.CarContext
import kr.carescape.multiverse.game.ChatLine
import kr.carescape.multiverse.game.Episode
import kr.carescape.multiverse.game.EpisodeGenerator
import kr.carescape.multiverse.game.GimmickKind
import kr.carescape.multiverse.game.Stage
import org.json.JSONObject

/** Prompts for the car's LLM and the parsing of its answers. No Android types, so it is unit tested. */
object LlmPrompt {

    /**
     * Asks for the story around a ready-made episode. The model may propose new numbers; [merge] keeps them
     * only when the car can reach them.
     */
    fun episodePrompt(base: Episode, ctx: CarContext): String {
        val stages = base.stages.filter { it.kind != GimmickKind.FINAL_CODE }.mapIndexed { i, s ->
            val numbers = when (s.kind) {
                GimmickKind.FLASHLIGHT -> ", blinks=${s.blinks} (2~4)"
                GimmickKind.SAFE_DIAL -> ", angle=${s.angle} (-120~120, 15의 배수, 0 제외 30 이상)"
                GimmickKind.TEMPERATURE -> ", temperature=${s.temperature} (17.0~27.0, 0.5 단위)"
                else -> ""
            }
            "${i + 1}. ${s.kind.label}: digit=${s.digit}$numbers"
        }.joinToString("\n")
        return """
            너는 자동차 안에서 부모와 아이가 함께 하는 방탈출 게임의 게임마스터야.
            테마: ${base.theme.title} (${base.theme.genre}), 등장인물: ${base.theme.npcRole} ${base.theme.npcName}
            지금 차량 상황: 장소 ${ctx.place}, 날씨 ${ctx.weather}, ${if (ctx.night) "밤" else "낮"}, 배터리 ${ctx.soc?.let { "$it%" } ?: "알 수 없음"}
            퍼즐 순서와 값(바꾸고 싶으면 괄호 안 범위에서만):
            $stages
            규칙: 아이가 이해할 쉬운 말, 한 문장 60자 이내, 무섭더라도 잔인한 표현 금지, 운전 중 행동 지시 금지.
            JSON 하나만 답해. stages는 위 퍼즐 순서대로 ${base.stages.size - 1}개. 숫자를 바꾸지 않을 거면 위에 적힌 값을 그대로 적어. 형식:
            {"title":"에피소드 제목","intro":"시작 대사 2문장","outro":"탈출 성공 대사","final_line":"최종 암호 요청 대사",
             "stages":[{"title":"단계 제목","line":"단계 시작 대사","hint":"힌트","digit":"위 digit","blinks":"위 blinks","angle":"위 angle","temperature":"위 temperature"}]}
        """.trimIndent()
    }

    fun replyPrompt(episode: Episode, stage: Stage, ctx: CarContext, history: List<ChatLine>, question: String): String {
        val talk = history.takeLast(6).joinToString("\n") { (if (it.fromPlayer) "플레이어: " else "${episode.theme.npcName}: ") + it.text }
        return """
            너는 방탈출 게임 "${episode.title}"의 ${episode.theme.npcRole} ${episode.theme.npcName}이야. 그 인물처럼 말해.
            장소 ${ctx.place}, 날씨 ${ctx.weather}. 지금 퍼즐: ${stage.title} (${stage.kind.label}). 힌트: ${stage.hint}
            절대 최종 암호 숫자를 직접 말하지 마. 운전 조작(출발, 가속)을 시키지 마. 두 문장 이내로 답해.
            $talk
            플레이어: $question
            ${episode.theme.npcName}:
        """.trimIndent()
    }

    /** The first {...} block in the answer (models like to wrap JSON in ``` fences or add a sentence). */
    fun extractJson(text: String): JSONObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()
    }

    /** Puts the model's story onto [base]. Anything missing or out of range keeps the base value. */
    fun merge(base: Episode, answer: String): Episode? {
        val o = extractJson(answer) ?: return null
        val arr = o.optJSONArray("stages")
        var gi = 0
        val stages = base.stages.map { s ->
            if (s.kind == GimmickKind.FINAL_CODE) {
                s.copy(line = o.optString("final_line").takeIf { it.isNotBlank() }?.let(::clean) ?: s.line)
            } else {
                val p = arr?.optJSONObject(gi++)
                if (p == null) s
                else EpisodeGenerator.sanitize(
                    s, s.copy(
                        title = clean(p.optString("title")), line = clean(p.optString("line")), hint = clean(p.optString("hint")),
                        digit = p.optInt("digit", -1), blinks = p.optInt("blinks", 0), angle = p.optInt("angle", 0),
                        temperature = p.optDouble("temperature", 0.0).toFloat()
                    )
                )
            }
        }
        return base.copy(
            title = clean(o.optString("title")).ifBlank { base.title },
            intro = clean(o.optString("intro")).ifBlank { base.intro },
            outro = clean(o.optString("outro")).ifBlank { base.outro },
            stages = stages,
            source = "llm"
        )
    }

    /** Keeps answers short enough to read aloud and free of markup. */
    fun clean(text: String): String = text.replace(Regex("[*#`]"), "").replace("\\n", " ").trim().take(160)

    /** A spoken NPC answer: first two sentences, never the final code. */
    fun cleanReply(text: String, code: String): String {
        val one = clean(text.substringBefore("플레이어:"))
        val sentences = one.split(Regex("(?<=[.!?…])\\s+")).filter { it.isNotBlank() }.take(2).joinToString(" ")
        return if (code.isNotEmpty() && sentences.replace(" ", "").contains(code)) "그건 너희가 직접 찾아야 해!" else sentences
    }
}
