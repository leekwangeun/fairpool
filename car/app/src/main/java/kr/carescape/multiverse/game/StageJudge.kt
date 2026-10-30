package kr.carescape.multiverse.game

import kr.carescape.multiverse.vehicle.Seat
import kr.carescape.multiverse.vehicle.Signals
import kr.carescape.multiverse.vehicle.Turn
import kotlin.math.abs

/**
 * Decides from two consecutive vehicle readings whether the players moved the current puzzle forward.
 * Pure functions only, so every gimmick is covered by unit tests without a car.
 */
object StageJudge {
    const val ANGLE_TOLERANCE = 12f
    const val TEMP_TOLERANCE = 0.25f

    /** One step: [before] and [now] are consecutive readings, [p] what the stage had so far. */
    fun step(stage: Stage, childSeat: Seat, before: Signals, now: Signals, p: StageProgress): StageProgress {
        if (p.done) return p
        return when (stage.kind) {
            GimmickKind.DEFROST -> if (now.defrostOn) done() else p.copy(ratio = 0f)

            GimmickKind.PURIFY -> {
                val parts = listOf(now.recirculation, now.airCleaner).count { it }
                if (parts == 2) done() else p.copy(ratio = parts / 2f)
            }

            GimmickKind.FLASHLIGHT -> {
                val wasOn = before.readingLight[childSeat] == true
                val isOn = now.readingLight[childSeat] == true
                val count = if (!wasOn && isOn) p.count + 1 else p.count
                if (count >= stage.blinks) done() else p.copy(count = count, ratio = count / stage.blinks.toFloat())
            }

            GimmickKind.SAFE_DIAL -> {
                if (!p.armed) {
                    // phase 1: the wheel on the mark while the brake is pressed "clicks" the dial
                    val onMark = abs(now.steeringAngle - stage.angle) <= ANGLE_TOLERANCE
                    if (onMark && now.brake) p.copy(armed = true, ratio = 0.5f)
                    else p.copy(ratio = 0.5f * dialCloseness(now.steeringAngle, stage.angle))
                } else {
                    // phase 2: count the turn signal switching on to the right side
                    val rising = before.turn != stage.turn && now.turn == stage.turn
                    val count = if (rising) p.count + 1 else p.count
                    if (count >= stage.turnCount) done()
                    else p.copy(count = count, ratio = 0.5f + 0.5f * count / stage.turnCount)
                }
            }

            GimmickKind.TEMPERATURE -> {
                val t = now.hvacTempC
                if (t != null && abs(t - stage.temperature) <= TEMP_TOLERANCE) done()
                else p.copy(ratio = if (t == null) 0f else (1f - (abs(t - stage.temperature) / 10f)).coerceIn(0f, 0.95f))
            }

            // answered on screen or by voice, see [answerWindow] and [answerCode]
            GimmickKind.LIGHT_SHARD, GimmickKind.FINAL_CODE -> p
        }
    }

    /** 1 when the wheel is on the mark, falling to 0 at 180 degrees away. */
    fun dialCloseness(angle: Float, target: Int): Float = (1f - abs(angle - target) / 180f).coerceIn(0f, 1f)

    fun answerWindow(stage: Stage, picked: Seat, p: StageProgress): StageProgress =
        if (stage.kind == GimmickKind.LIGHT_SHARD && picked == stage.window) done()
        else p.copy(wrongAnswers = p.wrongAnswers + 1)

    fun answerCode(code: String, answer: String, p: StageProgress): StageProgress =
        // contains, not equals: speech can add a stray digit word around the code ("이거야 사칠일공구" reads as 2 + code)
        if (code.isNotEmpty() && digitsIn(answer).contains(code)) done() else p.copy(wrongAnswers = p.wrongAnswers + 1)

    private fun done() = StageProgress(done = true, ratio = 1f)

    private val spoken = mapOf(
        "공" to '0', "영" to '0', "빵" to '0', "일" to '1', "하나" to '1', "이" to '2', "둘" to '2', "삼" to '3', "셋" to '3',
        "사" to '4', "넷" to '4', "오" to '5', "다섯" to '5', "육" to '6', "여섯" to '6', "칠" to '7', "일곱" to '7',
        "팔" to '8', "여덟" to '8', "구" to '9', "아홉" to '9'
    )

    /**
     * The digits in what the child typed or said: "4 7 1 0 9", "사칠일공구" and "넷, 일곱, 하나, 영, 아홉" all give "47109".
     * Longer words win ("여섯" before "여"), anything else is skipped.
     */
    fun digitsIn(text: String): String {
        val out = StringBuilder()
        var i = 0
        val words = spoken.keys.sortedByDescending { it.length }
        while (i < text.length) {
            val c = text[i]
            if (c.isDigit()) {
                out.append(c); i++; continue
            }
            val w = words.firstOrNull { text.startsWith(it, i) }
            if (w != null) {
                out.append(spoken.getValue(w)); i += w.length
            } else i++
        }
        return out.toString()
    }

    /** "운전석", "조수석", "뒷좌석 왼쪽", "왼쪽 뒤" ... from a spoken answer, or null. */
    fun windowIn(text: String): Seat? {
        val t = text.replace(" ", "")
        val rear = t.contains("뒤") || t.contains("뒷") || t.contains("2열")
        val left = t.contains("왼")
        val right = t.contains("오른")
        return when {
            t.contains("운전") -> Seat.DRIVER
            t.contains("조수") -> Seat.FRONT_RIGHT
            rear && left -> Seat.REAR_LEFT
            rear && right -> Seat.REAR_RIGHT
            left -> Seat.DRIVER
            right -> Seat.FRONT_RIGHT
            else -> null
        }
    }
}
