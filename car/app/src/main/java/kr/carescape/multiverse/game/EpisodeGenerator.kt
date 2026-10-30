package kr.carescape.multiverse.game

import kr.carescape.multiverse.vehicle.Seat
import kr.carescape.multiverse.vehicle.Turn
import kotlin.math.abs
import kotlin.random.Random

/**
 * The in-app game master. It builds a whole episode from the theme, the car's situation and a seed, so the same
 * seed gives the same episode (tests) and a new seed gives new numbers (replay).
 *
 * The car's LLM (ai/GameMaster) rewrites the story on top of this, but the puzzle values always come from here
 * or pass [Limits] first, so every puzzle can be solved with the car's real controls.
 */
object EpisodeGenerator {

    /** What the car can actually do; any value outside these is replaced. */
    object Limits {
        val angles = listOf(-120, -90, -75, -60, -45, -30, 30, 45, 60, 75, 90, 120)
        val blinks = 2..4
        val turnCount = 1..3
        val temperatures = (34..54).map { it / 2f } // 17.0 .. 27.0 in 0.5 steps

        fun angleOk(a: Int) = abs(a) in 30..120 && a % 15 == 0
        fun temperatureOk(t: Float) = t in 17f..27f && (t * 2) == (t * 2).toInt().toFloat()
    }

    private val windows = listOf(Seat.DRIVER, Seat.FRONT_RIGHT, Seat.REAR_LEFT, Seat.REAR_RIGHT)

    fun generate(theme: Theme, ctx: CarContext, seed: Long): Episode {
        val r = Random(seed)
        val digits = List(theme.kinds.size) { r.nextInt(10) }
        val stages = theme.kinds.mapIndexed { i, kind -> stage(theme, kind, digits[i], ctx, r) } + finalStage(theme)
        return Episode(theme, title(theme, ctx), intro(theme, ctx), outro(theme), stages, "builtin", seed)
    }

    // ---- words --------------------------------------------------------------------------------------------

    private val digitWords = listOf("영", "일", "이", "삼", "사", "오", "육", "칠", "팔", "구")
    private val nativeNumbers = listOf("", "한", "두", "세", "네", "다섯")

    fun digitWord(d: Int) = digitWords[d]

    /** 18.5 → "열여덟 도 반": the temperature riddle reads it in words so the child has to decode it. */
    fun temperatureWords(t: Float): String {
        val whole = t.toInt()
        val tens = listOf("", "열", "스물")[whole / 10]
        val ones = listOf("", "하나", "둘", "셋", "넷", "다섯", "여섯", "일곱", "여덟", "아홉")[whole % 10]
        val word = when {
            whole % 10 == 0 -> tens
            whole / 10 == 2 && whole % 10 != 0 -> "스물" + ones
            else -> tens + ones
        }
        return if (t - whole >= 0.5f) "$word 도 반" else "$word 도"
    }

    fun times(n: Int) = "${nativeNumbers.getOrElse(n) { n.toString() }} 번"

    fun angleWords(a: Int) = "${if (a < 0) "왼쪽" else "오른쪽"}으로 ${abs(a)}도"

    fun turnWords(t: Turn) = if (t == Turn.LEFT) "왼쪽" else "오른쪽"

    // ---- the episode frame --------------------------------------------------------------------------------

    private fun title(theme: Theme, ctx: CarContext) = when (theme) {
        Theme.SUBMARINE -> if (ctx.placeKind == PlaceKind.SEA) "${ctx.place} 앞바다의 해적 금고" else "잠긴 보물 잠수함"
        Theme.CODE_RED -> "코드 레드 : 에너지 코어를 복구하라"
        Theme.GHOST_TAXI -> if (ctx.rain) "빗속의 히치하이커" else "자정의 히치하이커"
        Theme.DETECTIVE -> "사라진 다이아몬드"
    }

    private fun intro(theme: Theme, ctx: CarContext): String = when (theme) {
        Theme.SUBMARINE ->
            "여기는 잠수함 노틸호! ${ctx.place} 아래 깊은 바다에 해적의 금고가 가라앉아 있어요. " +
                "기관장님은 앞자리 장치를, 탐정님은 단서를 맡아 주세요. 다섯 개의 숫자를 모으면 금고가 열려요!"
        Theme.CODE_RED -> {
            val soc = ctx.soc
            val energy = if (soc != null) "지금 우주선 에너지는 $soc%! ${minOf(soc + 35, 100)}%가 되기 전에" else "에너지가 다 떨어지기 전에"
            "경고, 경고! 코드 레드 발생! 우주 먼지가 우주선을 덮쳤어요. $energy 에너지 코어 암호를 풀어야 해요."
        }
        Theme.GHOST_TAXI -> {
            val weather = if (ctx.rain) "빗소리가 지붕을 두드리는 밤" else "고요한 자정"
            "$weather, ${ctx.place}. 아무도 타지 않았는데 뒷좌석 문이 닫히는 소리가 들렸어요. " +
                "누군가 이 차에 탔어요. 그의 원한을 풀어 줄 다섯 숫자를 찾으면 우리는 탈출할 수 있어요."
        }
        Theme.DETECTIVE ->
            "${ctx.place}에서 다이아몬드가 사라졌어요. 용의자는 셋, 운전기사, 가정부, 정원사. " +
                "모두 이 차 안에 단서를 숨겼어요. 진술의 모순을 찾고 금고를 열어 증거품을 확보하세요."
    }

    private fun outro(theme: Theme) = when (theme) {
        Theme.SUBMARINE -> "금고가 열렸어요! 해적의 보물은 바로 글로브박스 안에 있어요!"
        Theme.CODE_RED -> "에너지 코어 복구 완료! 보급품 상자가 열렸어요. 글로브박스를 확인하세요!"
        Theme.GHOST_TAXI -> "고마워요... 이제 편히 갈 수 있어요. 글로브박스에 부적을 두고 갈게요."
        Theme.DETECTIVE -> "사건 해결! 범인이 숨긴 증거품이 글로브박스에서 나왔어요."
    }

    // ---- stages -------------------------------------------------------------------------------------------

    private fun stage(theme: Theme, kind: GimmickKind, digit: Int, ctx: CarContext, r: Random): Stage = when (kind) {
        GimmickKind.DEFROST -> {
            val (title, line) = when (theme) {
                Theme.SUBMARINE -> "김 서린 해류 암호" to "앞 유리에 바다 안개가 꼈어요! 안개를 걷어내면 해류 암호가 보여요."
                Theme.CODE_RED -> "얼어붙은 관측창" to "관측창이 우주 서리로 얼었어요. 녹이면 좌표 숫자가 보일 거예요."
                Theme.GHOST_TAXI -> "창문에 남은 손가락 글씨" to "누가 김 서린 창문에 손가락으로 무언가를 썼어요... 김을 걷어내 보세요."
                Theme.DETECTIVE -> "유리창의 지문" to "운전기사가 앞 유리에 입김을 불고 무언가를 적었다는 제보가 있어요."
            }
            Stage(kind, title, line,
                parentTask = "앞 유리 성애 제거 버튼(또는 사이드미러 열선)을 켜세요.",
                childTask = "김이 걷히면 나타나는 숫자를 크게 읽어 주세요.",
                hint = "공조 버튼 중에 앞 유리 모양 버튼을 찾아보세요.",
                digit = digit)
        }
        GimmickKind.LIGHT_SHARD -> {
            val window = windows[r.nextInt(windows.size)]
            val (title, line) = when (theme) {
                Theme.SUBMARINE -> "잠망경의 빛" to "모든 창이 깊은 바다처럼 어두워졌어요. 딱 한 곳에서만 빛이 들어와요!"
                Theme.CODE_RED -> "별빛 좌표" to "방어막이 창문을 막았어요. 한 창문에서만 별빛이 새어 들어와요."
                Theme.GHOST_TAXI -> "한 줄기 달빛" to "창문이 모두 검게 변했어요... 유령이 남긴 달빛 하나를 찾아요."
                Theme.DETECTIVE -> "용의자의 창문" to "커튼을 모두 내렸어요. 범인이 열어 둔 창문 하나가 있어요."
            }
            Stage(kind, title, line,
                parentTask = "아이가 말한 창문을 화면의 차 그림에서 눌러 주세요.",
                childTask = "어느 창문이 밝은지 찾아서 말해 주세요. (운전석, 조수석, 뒷좌석 왼쪽, 뒷좌석 오른쪽)",
                hint = "고개를 돌려 창문 네 개를 하나씩 보세요. 밝은 창은 딱 하나예요.",
                digit = digit, window = window)
        }
        GimmickKind.PURIFY -> {
            val (title, line) = when (theme) {
                Theme.SUBMARINE -> "산소 정화기" to "산소가 줄고 있어요! 바깥 물이 들어오지 않게 막고 정화기를 돌려요."
                Theme.CODE_RED -> "우주 먼지 실드" to "외부에서 우주 먼지가 들어와요! 외부 공기를 차단하고 정화 장치를 가동하세요!"
                Theme.GHOST_TAXI -> "검은 안개" to "차 안으로 검은 안개가 스며들어요... 바깥 공기를 막아야 해요."
                Theme.DETECTIVE -> "수면 가스" to "범인이 환기구로 수면 가스를 넣었어요! 정신을 잃기 전에 막아요."
            }
            Stage(kind, title, line,
                parentTask = "공조를 내기 순환으로 바꾸고 공기청정을 켜세요.",
                childTask = "게이지가 초록색이 될 때까지 '정화 중!'이라고 외쳐 주세요.",
                hint = "자동차 안에서 공기가 빙글 도는 화살표 버튼이 내기 순환이에요.",
                digit = digit)
        }
        GimmickKind.FLASHLIGHT -> {
            val blinks = r.nextInt(Limits.blinks.first, Limits.blinks.last + 1)
            val (title, line) = when (theme) {
                Theme.SUBMARINE -> "보물 지도 비추기" to "보물 지도가 어둠 속에 있어요. 탐정님의 손전등으로 ${times(blinks)} 비춰 주세요!"
                Theme.CODE_RED -> "모스 구조 신호" to "본부에 구조 신호를 보내야 해요. 불빛을 ${times(blinks)} 깜빡이세요!"
                Theme.GHOST_TAXI -> "깜빡이는 독서등" to "독서등이 혼자 깜빡였어요... 유령에게 대답하려면 ${times(blinks)} 깜빡여야 해요."
                Theme.DETECTIVE -> "암호 손전등" to "가정부는 손전등 신호로 공범과 연락했대요. 같은 신호를 ${times(blinks)} 보내 보세요."
            }
            Stage(kind, title, line,
                parentTask = "아이가 독서등을 켜고 끌 수 있게 도와주세요. (운전석 장치는 쓰지 않아요)",
                childTask = "내 자리 머리 위 독서등을 ${times(blinks)} 켰다 끄세요.",
                hint = "천장의 작은 불빛 스위치를 눌렀다 떼면 한 번이에요.",
                digit = digit, blinks = blinks)
        }
        GimmickKind.SAFE_DIAL -> {
            val angle = Limits.angles[r.nextInt(Limits.angles.size)]
            val turn = if (r.nextBoolean()) Turn.LEFT else Turn.RIGHT
            val count = r.nextInt(Limits.turnCount.first, Limits.turnCount.last + 1)
            val (title, line) = when (theme) {
                Theme.SUBMARINE -> "잠망경 조준" to "잠망경을 금고 쪽으로 돌려야 해요! 탐정님이 기관장님께 방향을 알려 주세요."
                Theme.CODE_RED -> "코어 레버" to "에너지 코어 레버를 맞춰야 해요. 조종간을 정확히 돌리고 고정하세요."
                Theme.GHOST_TAXI -> "저주의 다이얼" to "핸들이 차갑게 얼어붙었어요... 유령이 알려 준 방향으로 돌려야 해요."
                Theme.DETECTIVE -> "용의자의 금고" to "정원사의 금고 다이얼이에요. 진술서에 적힌 각도로 돌려 보세요."
            }
            Stage(kind, title, line,
                parentTask = "아이의 구령에 맞춰 핸들을 돌리고 브레이크를 밟은 뒤 방향지시등을 켜세요.",
                childTask = "단서 카드를 읽고 '${angleWords(angle)}, 브레이크! ${turnWords(turn)} 깜빡이 ${times(count)}!'이라고 알려 주세요.",
                hint = "핸들은 바늘이 초록 칸에 들어올 때까지 천천히 돌리세요.",
                digit = digit, angle = angle, turn = turn, turnCount = count)
        }
        GimmickKind.TEMPERATURE -> {
            val pool = if (theme == Theme.GHOST_TAXI) Limits.temperatures.filter { it <= 19.5f } else Limits.temperatures
            val t = pool[r.nextInt(pool.size)]
            val (title, line) = when (theme) {
                Theme.SUBMARINE -> "수온 맞추기" to "잠수함 안 온도를 바다 온도와 같게 맞춰야 문이 열려요."
                Theme.CODE_RED -> "코어 냉각" to "코어가 과열됐어요! 정확한 냉각 온도를 맞추세요."
                Theme.GHOST_TAXI -> "유령이 기억하는 온도" to "그날 밤 이 차 안은 몹시 추웠대요... 그 온도를 기억해 내세요."
                Theme.DETECTIVE -> "온실의 온도" to "정원사의 온실 온도계에 단서가 있어요."
            }
            Stage(kind, title, line,
                parentTask = "공조 온도를 아이가 말한 숫자로 맞추세요.",
                childTask = "쪽지를 읽어 주세요: '${temperatureWords(t)}'. 몇 도인지 숫자로 알려 주세요.",
                hint = "'반'은 0.5도예요.",
                digit = digit, temperature = t)
        }
        GimmickKind.FINAL_CODE -> finalStage(theme)
    }

    private fun finalStage(theme: Theme): Stage {
        val line = when (theme) {
            Theme.SUBMARINE -> "다섯 숫자를 모두 모았어요! 탐정님, 금고 암호를 크게 외쳐 주세요!"
            Theme.CODE_RED -> "모든 회로 연결 완료. 에너지 코어 암호를 입력하세요!"
            Theme.GHOST_TAXI -> "이제 마지막이에요... 내 이름 대신 그 숫자를 불러 줘요."
            Theme.DETECTIVE -> "증거가 모였어요. 범인의 금고 암호를 말해 보세요!"
        }
        return Stage(GimmickKind.FINAL_CODE, "최종 암호", line,
            parentTask = "아이가 외친 암호가 맞는지 함께 확인하세요.",
            childTask = "모은 숫자 다섯 개를 순서대로 말하거나 자판으로 입력하세요.",
            hint = "왼쪽 아래 '모은 단서'에 숫자가 순서대로 있어요.")
    }

    /** Keeps an LLM-proposed value only when the car can actually reach it. */
    fun sanitize(stage: Stage, proposed: Stage): Stage = stage.copy(
        title = proposed.title.ifBlank { stage.title },
        line = proposed.line.ifBlank { stage.line },
        hint = proposed.hint.ifBlank { stage.hint },
        digit = if (stage.digit >= 0 && proposed.digit in 0..9) proposed.digit else stage.digit,
        blinks = if (stage.kind == GimmickKind.FLASHLIGHT && proposed.blinks in Limits.blinks) proposed.blinks else stage.blinks,
        angle = if (stage.kind == GimmickKind.SAFE_DIAL && Limits.angleOk(proposed.angle)) proposed.angle else stage.angle,
        temperature = if (stage.kind == GimmickKind.TEMPERATURE && Limits.temperatureOk(proposed.temperature)) proposed.temperature else stage.temperature
    ).let { s ->
        // the child's task quotes the numbers, so rebuild it whenever a number changed
        when (s.kind) {
            GimmickKind.FLASHLIGHT -> s.copy(childTask = "내 자리 머리 위 독서등을 ${times(s.blinks)} 켰다 끄세요.")
            GimmickKind.SAFE_DIAL -> s.copy(childTask = "단서 카드를 읽고 '${angleWords(s.angle)}, 브레이크! ${turnWords(s.turn)} 깜빡이 ${times(s.turnCount)}!'이라고 알려 주세요.")
            GimmickKind.TEMPERATURE -> s.copy(childTask = "쪽지를 읽어 주세요: '${temperatureWords(s.temperature)}'. 몇 도인지 숫자로 알려 주세요.")
            else -> s
        }
    }
}
