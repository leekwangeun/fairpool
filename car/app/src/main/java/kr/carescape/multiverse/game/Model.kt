package kr.carescape.multiverse.game

import kr.carescape.multiverse.vehicle.Seat
import kr.carescape.multiverse.vehicle.Turn

/** The four themes of the proposal. Colours are ARGB so the engine can send them to the ambient light too. */
enum class Theme(
    val number: Int,
    val title: String,
    val genre: String,
    val audience: String,
    val npcName: String,
    val npcRole: String,
    val accent: Long,
    /** Light tint of [accent] for the NPC card and speech bubbles. */
    val soft: Long,
    /** Ambient colour while playing (sent to the car). */
    val ambient: Int,
    val kinds: List<GimmickKind>
) {
    SUBMARINE(
        1, "보물 잠수함", "키즈 어드벤처", "권장 5~10세 가족", "노틸", "잠수함 AI",
        0xFF1EA7E1, 0xFFE3F5FC, 0xFF1E7BFF.toInt(),
        listOf(GimmickKind.FLASHLIGHT, GimmickKind.DEFROST, GimmickKind.LIGHT_SHARD, GimmickKind.SAFE_DIAL, GimmickKind.PURIFY)
    ),
    CODE_RED(
        2, "코드 레드", "키즈 우주 모험", "충전 기다릴 때", "코어", "우주선 AI",
        0xFFFF6B3D, 0xFFFFEDE6, 0xFFFF3B1F.toInt(),
        listOf(GimmickKind.PURIFY, GimmickKind.DEFROST, GimmickKind.TEMPERATURE, GimmickKind.SAFE_DIAL, GimmickKind.FLASHLIGHT)
    ),
    GHOST_TAXI(
        3, "고스트 택시", "오싹 미스터리", "초등 고학년 이상", "자정의 승객", "유령 승객",
        0xFF7B61FF, 0xFFEEEAFF, 0xFF7B61FF.toInt(),
        listOf(GimmickKind.FLASHLIGHT, GimmickKind.DEFROST, GimmickKind.TEMPERATURE, GimmickKind.SAFE_DIAL, GimmickKind.LIGHT_SHARD)
    ),
    DETECTIVE(
        4, "밀실의 탐정", "추리 놀이", "온 가족", "용의자 셋", "AI 용의자",
        0xFFF5A623, 0xFFFFF4DF, 0xFFFFB347.toInt(),
        listOf(GimmickKind.LIGHT_SHARD, GimmickKind.TEMPERATURE, GimmickKind.SAFE_DIAL, GimmickKind.DEFROST, GimmickKind.FLASHLIGHT)
    );

    val fullTitle: String get() = "테마 $number $title"
}

/** The seven gimmicks of the proposal. [FINAL_CODE] is always the last stage. */
enum class GimmickKind(val label: String) {
    DEFROST("성애 제거"),
    LIGHT_SHARD("빛의 조각"),
    PURIFY("공기 정화"),
    FLASHLIGHT("손전등 신호"),
    SAFE_DIAL("금고 다이얼"),
    TEMPERATURE("온도 암호"),
    FINAL_CODE("최종 암호")
}

/** What the car is like right now; the AI game master writes the episode around it. */
data class CarContext(
    val place: String = "도심 주차장",
    val placeKind: PlaceKind = PlaceKind.CITY,
    val rain: Boolean = false,
    val night: Boolean = false,
    val soc: Int? = null,
    val hour: Int = 12,
    val childSeat: Seat = Seat.REAR_RIGHT
) {
    val weather: String get() = if (rain) "비" else "맑음"
}

enum class PlaceKind(val label: String) {
    SEA("바다"), MOUNTAIN("산"), CITY("도심"), REST_STOP("휴게소"), CHARGER("충전소")
}

/**
 * One puzzle. Only the fields its [kind] needs are used:
 * - DEFROST: [digit] appears when the defroster is on
 * - LIGHT_SHARD: [window] is the one clear window, the answer
 * - PURIFY: recirculation and air cleaner on
 * - FLASHLIGHT: the child's reading light turned on [blinks] times
 * - SAFE_DIAL: wheel within 12 degrees of [angle] with the brake pressed, then [turn] signalled [turnCount] times
 * - TEMPERATURE: the HVAC set temperature equals [temperature]
 * - FINAL_CODE: the answer is the episode code
 */
data class Stage(
    val kind: GimmickKind,
    val title: String,
    /** What the NPC says when the stage opens (read aloud). */
    val line: String,
    val parentTask: String,
    val childTask: String,
    val hint: String,
    /** The code digit this stage reveals (-1 for FINAL_CODE). */
    val digit: Int = -1,
    val window: Seat? = null,
    val blinks: Int = 0,
    val angle: Int = 0,
    val turn: Turn = Turn.OFF,
    val turnCount: Int = 0,
    val temperature: Float = 0f
)

data class Episode(
    val theme: Theme,
    val title: String,
    val intro: String,
    val outro: String,
    val stages: List<Stage>,
    /** "llm" when the car's LLM wrote it, "builtin" when the in-app generator did. */
    val source: String,
    val seed: Long
) {
    /** The final code: every digit the gimmick stages revealed, in order. */
    val code: String get() = stages.filter { it.digit >= 0 }.joinToString("") { it.digit.toString() }
}

/** How far the current stage has got (the judge's memory between two readings). */
data class StageProgress(
    val count: Int = 0,
    val armed: Boolean = false,
    val done: Boolean = false,
    /** 0..1 for the gauge on the play screen. */
    val ratio: Float = 0f,
    val wrongAnswers: Int = 0
)

enum class Phase { HOME, SAFETY, PLAYING, PAUSED, CLEARED }

enum class PauseReason(val title: String) {
    DOOR_OPEN("문이 열렸어요"),
    BELT_OFF("아이 안전벨트를 매 주세요"),
    CHILD_LEFT_SEAT("아이가 자리를 비웠어요"),
    DRIVING("차가 움직여요")
}

enum class CheckState { PASS, WAIT, FAIL, SKIPPED }

data class CheckItem(val label: String, val state: CheckState, val detail: String = "")

data class ChatLine(val fromPlayer: Boolean, val text: String)

data class GameState(
    val phase: Phase = Phase.HOME,
    val theme: Theme? = null,
    val childSeat: Seat = Seat.REAR_RIGHT,
    val context: CarContext = CarContext(),
    val episode: Episode? = null,
    val generating: Boolean = false,
    val checklist: List<CheckItem> = emptyList(),
    val stageIndex: Int = 0,
    val progress: StageProgress = StageProgress(),
    /** Shown over the gimmick art for a moment after a stage is passed. */
    val justPassed: Boolean = false,
    val revealed: List<Int> = emptyList(),
    val pauseReason: PauseReason? = null,
    val pauseDetail: String = "",
    val startedAt: Long = 0,
    val clearedAt: Long = 0,
    val hintsUsed: Int = 0,
    val showHint: Boolean = false,
    val chat: List<ChatLine> = emptyList(),
    val chatOpen: Boolean = false,
    val listening: Boolean = false,
    val restored: List<String> = emptyList(),
    /** A one-off notice under the top bar (e.g. "차가 움직여 게임을 끝냈어요"). */
    val notice: String? = null
) {
    val stage: Stage? get() = episode?.stages?.getOrNull(stageIndex)
    val stageCount: Int get() = episode?.stages?.size ?: 0
    val allChecksPassed: Boolean get() = checklist.isNotEmpty() && checklist.all { it.state == CheckState.PASS || it.state == CheckState.SKIPPED }
}
