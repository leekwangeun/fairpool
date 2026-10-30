package kr.carescape.multiverse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.carescape.multiverse.game.ContextReader
import kr.carescape.multiverse.game.GameEngine
import kr.carescape.multiverse.game.GameState
import kr.carescape.multiverse.game.PlaceKind
import kr.carescape.multiverse.game.Theme
import kr.carescape.multiverse.vehicle.Seat
import kr.carescape.multiverse.vehicle.Signals

/** E0: pick a theme, put the treasure in, say where the child sits. */
@Composable
fun HomeScreen(s: GameState, signals: Signals, onSettings: () -> Unit) {
    val recommended = ContextReader.recommended(s.context)
    Column(Modifier.fillMaxSize().background(C.bg)) {
        TopBar(
            left = { Text("차량탈출 : AI 멀티버스", style = T.big) },
            middle = { ContextChips(s.context) },
            right = { GhostButton("설정", onClick = onSettings) }
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(48.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            s.notice?.let { NoticeBar(it) }
            Text("오늘의 방탈출을 고르세요", style = T.title)
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                for (theme in Theme.entries) {
                    ThemeCard(theme, recommendTag(theme, s), theme == recommended, Modifier.weight(1f)) { GameEngine.chooseTheme(theme) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Card(Modifier.weight(1f)) {
                    Text("보물 넣기", style = T.big)
                    Text("글로브박스에 간식이나 작은 선물을 넣고 닫아 주세요. 시작하면 잠기고, 마지막 암호를 풀면 열립니다.", style = T.body)
                    Text("AI 게임마스터: ${GameEngine.masterName}", style = T.sub)
                }
                Card(Modifier.weight(1f)) {
                    Text("크루", style = T.big)
                    Text("1열 기관장: 운전석 (보호자)", style = T.body)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("2열 탐정:", style = T.body)
                        for (seat in listOf(Seat.FRONT_RIGHT, Seat.REAR_LEFT, Seat.REAR_RIGHT)) {
                            val sitting = signals.occupied[seat] == true || signals.belted[seat] == true
                            Chip(seat.label + if (sitting) " (착석)" else "", filled = if (s.childSeat == seat) C.success else null) {
                                GameEngine.setChildSeat(seat)
                            }
                        }
                    }
                    Text("아이는 단서를 읽고 말하고, 운전석 장치는 보호자만 만져요.", style = T.sub)
                }
            }
        }
    }
}

private fun recommendTag(theme: Theme, s: GameState): String = when (theme) {
    Theme.SUBMARINE -> if (s.context.placeKind == PlaceKind.SEA) "지금 바다 근처" else "휴게소, 바다 추천"
    Theme.CODE_RED -> if (s.context.soc != null) "충전 중 추천 (${s.context.soc}%)" else "충전 대기 추천"
    Theme.GHOST_TAXI -> if (s.context.rain) "비 오는 밤 특별편 해금" else "밤, 비 오는 날 추천"
    Theme.DETECTIVE -> "온 가족 추천"
}

@Composable
private fun ThemeCard(theme: Theme, tag: String, highlight: Boolean, modifier: Modifier, onPick: () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier.height(560.dp).clip(shape)
            .background(Brush.verticalGradient(listOf(theme.gradientTop.asColor(), theme.gradientBottom.asColor())))
            .border(if (highlight) 4.dp else 0.dp, if (highlight) theme.accent.asColor() else Color.Transparent, shape)
            .clickable(onClick = onPick).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("${theme.number}", style = T.title.copy(fontSize = 96.sp, color = theme.accent.asColor(), fontWeight = FontWeight.Black))
        Spacer(Modifier.weight(1f))
        Text("테마 ${theme.number}", style = T.sub.copy(color = Color.White.copy(alpha = 0.8f)))
        Text(theme.title, style = T.title.copy(fontSize = 40.sp, color = Color.White))
        Text(theme.genre, style = T.body.copy(color = Color.White))
        Text(theme.audience, style = T.sub.copy(color = Color.White.copy(alpha = 0.8f)))
        Chip(tag, filled = if (highlight) theme.accent.asColor() else null)
        Box(
            Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) { Text("고르기", style = T.big.copy(color = Color.White)) }
    }
}

@Composable
fun NoticeBar(text: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.cardSoft).padding(horizontal = 28.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, style = T.body, modifier = Modifier.weight(1f))
        Chip("닫기") { GameEngine.dismissNotice() }
    }
}
