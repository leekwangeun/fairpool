package kr.carescape.multiverse.ai

import kr.carescape.multiverse.game.CarContext
import kr.carescape.multiverse.game.ChatLine
import kr.carescape.multiverse.game.Episode
import kr.carescape.multiverse.game.EpisodeGenerator
import kr.carescape.multiverse.game.GimmickKind
import kr.carescape.multiverse.game.Stage
import kr.carescape.multiverse.game.Theme

/** The AI game master: writes the episode and plays the NPC. */
interface GameMaster {
    val name: String
    suspend fun episode(theme: Theme, ctx: CarContext, seed: Long): Episode
    suspend fun reply(episode: Episode, stage: Stage, ctx: CarContext, history: List<ChatLine>, question: String): String
}

/**
 * The in-app game master. Always available, no network, no model; also the fallback whenever the car's
 * LLM is missing, slow or answers with something unusable.
 */
object BuiltinMaster : GameMaster {
    override val name = "내장 생성기"

    override suspend fun episode(theme: Theme, ctx: CarContext, seed: Long) = EpisodeGenerator.generate(theme, ctx, seed)

    override suspend fun reply(episode: Episode, stage: Stage, ctx: CarContext, history: List<ChatLine>, question: String): String {
        val q = question.replace(" ", "")
        val theme = episode.theme
        return when {
            q.contains("힌트") || q.contains("도와") || q.contains("모르겠") -> stage.hint
            q.contains("정답") || q.contains("암호") || q.contains("비밀번호") ->
                if (stage.kind == GimmickKind.FINAL_CODE) "암호는 너희가 모은 숫자 속에 있어. 처음 찾은 숫자부터 순서대로 말해 봐!"
                else "아직 숫자를 다 모으지 않았어. 지금 단서부터 풀어 보자!"
            theme == Theme.DETECTIVE && suspect(q) != null -> suspect(q)!!
            q.contains("누구") || q.contains("이름") -> "나는 ${theme.npcRole} ${theme.npcName}. ${intro(theme)}"
            q.contains("어디") || q.contains("여기") -> "여기는 ${ctx.place}. ${if (ctx.rain) "빗소리가 들리지?" else "창밖을 잘 봐."} 단서는 이 차 안에 있어."
            else -> flavour(theme, (q.hashCode() and 0x7fffffff) % 3, stage)
        }
    }

    private fun intro(theme: Theme) = when (theme) {
        Theme.SUBMARINE -> "보물을 지키는 잠수함 AI야. 우리 같이 금고를 열자!"
        Theme.CODE_RED -> "이 우주선의 에너지 코어를 관리해. 지금 아주 위험해!"
        Theme.GHOST_TAXI -> "...오래전 비 오는 밤에 이 택시를 탔던 손님이야."
        Theme.DETECTIVE -> "우리는 용의자 셋이야. 운전기사, 가정부, 정원사. 누구에게 물어볼래?"
    }

    private fun suspect(q: String): String? = when {
        q.contains("운전") || q.contains("기사") -> "운전기사: 저는 그 시간에 차 안에서 성애를 닦고 있었어요. 앞 유리에 뭐가 써 있었냐고요? 글쎄요..."
        q.contains("가정부") -> "가정부: 손전등이요? 정전이 돼서 잠깐 켰다 껐을 뿐이에요. 몇 번이었는지는 기억 안 나요."
        q.contains("정원") -> "정원사: 저는 온실에 있었어요. 온실은 늘 같은 온도로 맞춰 두죠. 금고요? 각도가 중요하다고만 들었어요."
        else -> null
    }

    private fun flavour(theme: Theme, i: Int, stage: Stage): String {
        val lines = when (theme) {
            Theme.SUBMARINE -> listOf("부글부글! 좋은 질문이야. ${stage.title} 단서를 먼저 풀어 볼까?", "기관장님과 탐정님이 힘을 합치면 금방이야!", "바다 깊은 곳에선 작은 불빛도 큰 단서가 돼.")
            Theme.CODE_RED -> listOf("삐빅! 분석 중... ${stage.title}부터 해결해야 해!", "에너지가 계속 올라가고 있어. 서둘러!", "우주에서는 협동이 생명이야.")
            Theme.GHOST_TAXI -> listOf("...춥지 않니? 나는 그날 밤이 너무 추웠어...", "...뒤를 돌아보지 마. 단서만 봐...", "...${stage.title}... 그걸 풀면 알게 될 거야...")
            Theme.DETECTIVE -> listOf("흠, 날카로운 질문이군요. 진술을 다시 들어 보세요.", "범인은 늘 작은 실수를 하죠. ${stage.title}에 그 실수가 있어요.", "모순을 찾으면 금고가 열립니다.")
        }
        return lines[i]
    }
}
