package kr.fairpool.car

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kr.fairpool.car.data.Config
import kr.fairpool.car.data.DemoPlaces
import kr.fairpool.car.data.Place
import kr.fairpool.car.data.Rpc
import kr.fairpool.car.data.RpcException
import kr.fairpool.car.data.args
import kr.fairpool.car.map.CourseMap
import kr.fairpool.car.trip.Phase
import kr.fairpool.car.trip.PostResult
import kr.fairpool.car.trip.SeatSlot
import kr.fairpool.car.trip.TripEngine
import kr.fairpool.car.trip.TripService
import kr.fairpool.car.trip.distanceKm
import kr.fairpool.car.trip.Voice
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat

private val Bg = Color(0xFFF3F4F7)
private val CardBg = Color.White
private val Blue = Color(0xFF3478F6)
private val BlueSoft = Color(0xFFEAF1FF)
private val Ink = Color(0xFF191F28)
private val Sub = Color(0xFF6B7684)
private val Line = Color(0xFFE5E8EB)
private val Green = Color(0xFF1FA463)
private val Orange = Color(0xFFF08C00)

private enum class Screen { SETUP, HOME, DRIVER, INVITE, SEATS, DRIVING, SETTLE, FOLDERS, SETTINGS }

private fun won(v: Long) = NumberFormat.getInstance().format(v) + "원"

/** AAOS driving restrictions (CarUxRestrictions): true while the car requires a distraction-optimized screen. */
private val uxRestricted = MutableStateFlow(false)

/** The Pleos emulator (or any AVD): the navi cannot follow its mock GPS, so the app asks the router for ETAs itself. */
private fun isEmulator(): Boolean =
    Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("emulator") ||
        Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish" ||
        Build.PRODUCT.contains("sdk") || Build.MODEL.contains("Emulator")

class MainActivity : ComponentActivity() {
    private lateinit var config: Config
    private lateinit var rpc: Rpc
    private var car: Car? = null
    private var ux: CarUxRestrictionsManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = Config(this)
        rpc = Rpc(config)
        TripEngine.rpc = rpc
        TripEngine.fuelPrice = config.fuelPrice
        TripEngine.defaultEfficiency = config.efficiency.toDouble()
        TripEngine.preferSavedTable = config.useSavedTable
        TripEngine.routerEta = isEmulator()
        // speech answers on the L5 card, Pleos fused location and vehicle speed (all runtime permissions)
        val wanted = arrayOf(android.Manifest.permission.RECORD_AUDIO, "pleos.car.permission.FUSED_LOCATION", Car.PERMISSION_SPEED)
            .filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)
        watchDrivingRestrictions()
        setContent {
            // the Pleos display reports mdpi for a 2560x1440 panel; scale up so text reads at driving distance
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density * 1.35f, base.fontScale)) {
                MaterialTheme(colorScheme = lightColorScheme(primary = Blue, background = Bg, surface = CardBg)) {
                    App(config, rpc)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        TripEngine.uiVisible.value = true
        TripService.start(this)
    }

    override fun onStop() {
        TripEngine.uiVisible.value = false
        super.onStop()
    }

    @Deprecated("Deprecated in ComponentActivity")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // the service picks up the speed once CAR_SPEED is granted
        TripService.start(this)
    }

    override fun onDestroy() {
        runCatching { ux?.unregisterListener() }
        runCatching { car?.disconnect() }
        super.onDestroy()
    }

    /** While AAOS says the car is driving, only the driving screen (or a 정차 후 이용 view) is shown. */
    private fun watchDrivingRestrictions() {
        runCatching {
            val c = Car.createCar(this)
            car = c
            val m = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            ux = m
            uxRestricted.value = m.currentCarUxRestrictions?.isRequiresDistractionOptimization == true
            m.registerListener { r -> uxRestricted.value = r.isRequiresDistractionOptimization }
        }.onFailure { TripEngine.log("주행 제한 정보를 받지 못함: ${it.message}") }
    }
}

// ---- shared pieces ---------------------------------------------------------------------------------------

@Composable
private fun Card(modifier: Modifier = Modifier, padding: Int = 28, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(24.dp)).background(CardBg).padding(padding.dp),
        content = content
    )
}

@Composable
private fun Title(text: String, size: Int = 30) =
    Text(text, fontSize = size.sp, fontWeight = FontWeight.Bold, color = Ink, lineHeight = (size * 1.3).sp)

@Composable
private fun Body(text: String, color: Color = Sub, size: Int = 19) =
    Text(text, fontSize = size.sp, color = color, lineHeight = (size * 1.4).sp)

@Composable
private fun Btn(text: String, modifier: Modifier = Modifier, primary: Boolean = true, enabled: Boolean = true, onClick: () -> Unit) {
    val bg = when {
        !enabled -> Line
        primary -> Blue
        else -> CardBg
    }
    Box(
        modifier.height(84.dp).clip(RoundedCornerShape(20.dp)).background(bg)
            .then(if (!primary && enabled) Modifier.border(2.dp, Line, RoundedCornerShape(20.dp)) else Modifier)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = if (primary && enabled) Color.White else if (enabled) Ink else Sub)
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, modifier: Modifier = Modifier, sub: String? = null, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(if (selected) BlueSoft else Bg)
            .border(if (selected) 2.dp else 0.dp, if (selected) Blue else Bg, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = 16.dp)
    ) {
        Text(text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        if (sub != null) Text(sub, fontSize = 16.sp, color = if (selected) Blue else Sub)
    }
}

@Composable
private fun StatusPill(text: String, color: Color = Green) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(CardBg).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink)
    }
}

@Composable
private fun Header(status: String, statusColor: Color = Green, onSettings: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Blue), contentAlignment = Alignment.Center) {
            Text("F", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        }
        Spacer(Modifier.width(12.dp))
        Text("페어풀(FairPool)", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink,
            modifier = if (onSettings != null) Modifier.clickable(onClick = onSettings) else Modifier)
        Spacer(Modifier.weight(1f))
        StatusPill(status, statusColor)
    }
}

@Composable
private fun Qr(content: String, sizeDp: Int) {
    val bmp = remember(content) {
        val m = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 600, 600)
        val b = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888)
        for (x in 0 until m.width) for (y in 0 until m.height) b.setPixel(x, y, if (m[x, y]) 0xFF191F28.toInt() else 0xFFFFFFFF.toInt())
        b.asImageBitmap()
    }
    Image(bmp, contentDescription = "QR", modifier = Modifier.size(sizeDp.dp))
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, hidden: Boolean = false,
                  onDone: (() -> Unit)? = null) {
    OutlinedTextField(value, onChange, label = { Text(label, fontSize = 16.sp) }, singleLine = true,
        visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(imeAction = if (onDone != null) ImeAction.Done else ImeAction.Default),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 20.sp), modifier = modifier.fillMaxWidth())
}

@Composable
private fun Initial(name: String, color: Color = Blue, bg: Color = BlueSoft) {
    Box(Modifier.size(52.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Text(name.take(1), fontSize = 21.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

// ---- app --------------------------------------------------------------------------------------------------

@Composable
private fun App(config: Config, rpc: Rpc) {
    var screen by remember { mutableStateOf(if (config.vehicleSecret == null) Screen.SETUP else Screen.HOME) }
    var message by remember { mutableStateOf<String?>(null) }
    val trip by TripEngine.state.collectAsState()
    val restricted by uxRestricted.collectAsState()
    // driving: AAOS asks for a distraction-optimized screen, or the car reports it is moving
    val driving = restricted || (trip.speedKnown && !trip.stopped)

    Box(Modifier.fillMaxSize().background(Bg).padding(28.dp)) {
        if (driving && trip.phase == Phase.DRIVING) {
            DrivingScreen(canAct = false, onArrive = {})
        } else if (driving) {
            ParkedOnlyScreen()
        } else when (screen) {
            Screen.SETUP -> SetupScreen(config, rpc, onDone = { screen = Screen.HOME }, onDriver = { screen = Screen.DRIVER }, onSettings = { screen = Screen.SETTINGS })
            Screen.SETTINGS -> SettingsScreen(config, onBack = { screen = if (config.vehicleSecret == null) Screen.SETUP else Screen.HOME })
            Screen.DRIVER -> DriverScreen(config, rpc, onBack = { screen = Screen.HOME })
            Screen.HOME -> HomeScreen(config, rpc, onStart = { screen = Screen.INVITE }, onDriver = { screen = Screen.DRIVER },
                onFolders = { screen = Screen.FOLDERS }, onSettings = { screen = Screen.SETTINGS }, onMessage = { message = it })
            Screen.INVITE -> InviteScreen(config, rpc, onNext = { TripEngine.toSeats(); screen = Screen.SEATS }, onMessage = { message = it })
            Screen.SEATS -> SeatsScreen(onDepart = { TripEngine.depart(); screen = Screen.DRIVING })
            Screen.DRIVING -> DrivingScreen(canAct = trip.stopped, onArrive = { TripEngine.arrive(); screen = Screen.SETTLE })
            Screen.SETTLE -> SettleScreen(rpc, onDone = { TripEngine.reset(); screen = Screen.HOME }, onMessage = { message = it })
            Screen.FOLDERS -> FoldersScreen(config, rpc, onBack = { screen = Screen.HOME }, onMessage = { message = it })
        }
        message?.let { m ->
            LaunchedEffect(m) { delay(3500); message = null }
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp).clip(RoundedCornerShape(16.dp))
                .background(Ink).padding(horizontal = 28.dp, vertical = 18.dp)) {
                Text(m, color = Color.White, fontSize = 19.sp)
            }
        }
    }
    // keep the screen in step if the engine moved on (e.g. after a restart)
    LaunchedEffect(trip.phase) {
        val want = when (trip.phase) {
            Phase.INVITE -> Screen.INVITE
            Phase.SEATS -> Screen.SEATS
            Phase.DRIVING -> Screen.DRIVING
            Phase.ARRIVED, Phase.SETTLED -> Screen.SETTLE
            Phase.IDLE -> null
        }
        if (want != null && screen != want) screen = want
    }
}

/** Every screen but the driving one, while the car moves: no typing, settings or folders until it stops. */
@Composable
private fun ParkedOnlyScreen() {
    Column(Modifier.fillMaxSize()) {
        Header("주행 중", Orange)
        Card(Modifier.fillMaxWidth().weight(1f)) {
            Spacer(Modifier.weight(1f))
            Text("정차 후 이용할 수 있어요", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Ink,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Text("운전 중에는 화면을 조작하지 않아도 돼요. 차를 세우면 하던 화면으로 돌아가요.", fontSize = 20.sp, color = Sub,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.weight(1f))
        }
    }
}

// ---- L0 + vehicle link ------------------------------------------------------------------------------------

@Composable
private fun SetupScreen(config: Config, rpc: Rpc, onDone: () -> Unit, onDriver: () -> Unit, onSettings: () -> Unit) {
    val scope = rememberCoroutineScope()
    var linkCode by remember { mutableStateOf(config.linkCode) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Header("처음 설정", Orange, onSettings)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Card(Modifier.weight(1f).fillMaxHeight()) {
                Title("페어풀(FairPool) : 공정한 카풀 정산", 34)
                Spacer(Modifier.height(16.dp))
                Body("함께 탄 사람이 어디서 타고 내렸는지 차가 알아서 기록하고, 각자 만든 거리만큼 기름값을 나눠요.")
                Spacer(Modifier.height(32.dp))
                Body("1. 이 차를 등록해요", Ink, 22)
                Body("2. 운전자 휴대폰으로 연결 QR을 찍어요")
                Body("3. 정산 받을 계좌를 넣어 두면 동승자 페이지에 보여요")
                Spacer(Modifier.weight(1f))
                if (note.isNotEmpty()) Body(note, Orange, 17)
                Spacer(Modifier.height(12.dp))
                if (linkCode == null) {
                    Btn(if (busy) "등록 중..." else "이 차 등록하기", Modifier.fillMaxWidth(), enabled = !busy) {
                        busy = true
                        scope.launch {
                            if (!config.demo && !rpc.reachable()) {
                                config.demo = true
                                note = "서버에 연결할 수 없어 데모 모드로 진행해요(설정에서 바꿀 수 있어요)"
                            }
                            try {
                                val r = rpc.call("create_vehicle_link")
                                config.vehicleSecret = r.optString("vehicle_secret")
                                config.linkCode = r.optString("link_code")
                                linkCode = config.linkCode
                            } catch (e: Exception) {
                                note = "등록 실패: ${e.message}"
                            }
                            busy = false
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Btn("운전자 정보 넣기", Modifier.weight(1f), primary = false, onClick = onDriver)
                        Btn("시작하기", Modifier.weight(1f), onClick = onDone)
                    }
                }
            }
            Card(Modifier.weight(1f).fillMaxHeight()) {
                if (linkCode != null) {
                    Title("운전자 휴대폰 연결", 26)
                    Spacer(Modifier.height(8.dp))
                    Body("휴대폰 카메라로 찍으면 이 차와 연결돼요(10분 유효)")
                    Spacer(Modifier.height(24.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Qr("${config.siteUrl}/link/$linkCode", 380) }
                    Spacer(Modifier.height(12.dp))
                    Body("연결 코드 $linkCode", Ink)
                } else {
                    Title("이렇게 나눠요", 26)
                    Spacer(Modifier.height(16.dp))
                    Body("모두가 혼자 갈 때보다 적게 내고, 돌아가게 만든 사람이 그만큼 더 내요. 합계는 실제로 든 기름값과 통행료와 정확히 같아요.")
                }
            }
        }
    }
}

// ---- settings -------------------------------------------------------------------------------------------

@Composable
private fun SettingsScreen(config: Config, onBack: () -> Unit) {
    var server by remember { mutableStateOf(config.serverUrl) }
    var site by remember { mutableStateOf(config.siteUrl) }
    var key by remember { mutableStateOf(config.anonKey) }
    var demo by remember { mutableStateOf(config.demo) }
    var savedTable by remember { mutableStateOf(config.useSavedTable) }
    var price by remember { mutableStateOf(config.fuelPrice.toString()) }
    var efficiency by remember { mutableStateOf(config.efficiency.toString()) }
    var secret by remember { mutableStateOf(config.vehicleSecret ?: "") }
    Column(Modifier.fillMaxSize()) {
        Header("설정", Sub)
        Card(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            Field("서버 주소", server, { server = it })
            Spacer(Modifier.height(12.dp))
            Field("anon key (없으면 비움)", key, { key = it })
            Spacer(Modifier.height(12.dp))
            Field("QR 사이트 주소", site, { site = it })
            Spacer(Modifier.height(12.dp))
            Field("휘발유 단가 (원/L)", price, { price = it.filter(Char::isDigit) })
            Spacer(Modifier.height(12.dp))
            Field("기본 연비 (km/L, 차가 연비를 알려 주지 않을 때)", efficiency, { efficiency = it.filter { c -> c.isDigit() || c == '.' } })
            Spacer(Modifier.height(12.dp))
            Field("차 비밀값 (이미 등록한 차를 쓸 때)", secret, { secret = it.trim() }, hidden = true)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(demo, { demo = it })
                Spacer(Modifier.width(12.dp))
                Body("데모 모드 (서버 없이 앱 안에서 응답)", Ink)
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(savedTable, { savedTable = it })
                Spacer(Modifier.width(12.dp))
                Body("시연 대비: 출발 때 받아 둔 조합 거리표로 정산 (길찾기를 다시 하지 않음)", Ink)
            }
        }
        Spacer(Modifier.height(16.dp))
        Btn("저장", Modifier.fillMaxWidth()) {
            config.serverUrl = server
            config.siteUrl = site
            config.anonKey = key
            config.demo = demo
            config.useSavedTable = savedTable
            TripEngine.preferSavedTable = savedTable
            config.fuelPrice = price.toIntOrNull() ?: 1700
            config.vehicleSecret = secret.ifEmpty { null }
            TripEngine.fuelPrice = config.fuelPrice
            config.efficiency = efficiency.toFloatOrNull() ?: 11f
            TripEngine.defaultEfficiency = config.efficiency.toDouble()
            onBack()
        }
    }
}

// ---- L1-9 driver info ---------------------------------------------------------------------------------

@Composable
private fun DriverScreen(config: Config, rpc: Rpc, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var phone4 by remember { mutableStateOf("") }
    var bankName by remember { mutableStateOf("") }
    var account by remember { mutableStateOf("") }
    var holder by remember { mutableStateOf("") }
    var consent by remember { mutableStateOf(false) }
    var phone4Saved by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    val secret = config.vehicleSecret
    LaunchedEffect(Unit) {
        runCatching {
            val d = rpc.call("get_driver_info", args("p_vehicle_secret" to secret))
            name = d.optString("name")
            val b = rpc.call("get_bank_account", args("p_vehicle_secret" to secret))
            bankName = b.optString("bank_name"); account = b.optString("account"); holder = b.optString("holder")
            phone4Saved = d.optBoolean("phone4_set")
            consent = account.isNotEmpty()
        }
    }
    Column(Modifier.fillMaxSize()) {
        Header("주차 중")
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Card(Modifier.weight(1f).fillMaxHeight()) {
                Title("운전자 정보", 28)
                Body("내 휴대폰으로 보낸 초대와 정산을 열 때 확인해요")
                Spacer(Modifier.height(20.dp))
                Field("이름", name, { name = it })
                Spacer(Modifier.height(12.dp))
                Field(if (phone4Saved) "휴대폰 번호 뒤 4자리 (저장됨, 바꿀 때만 입력)" else "휴대폰 번호 뒤 4자리", phone4,
                    { phone4 = it.filter(Char::isDigit).take(4) }, hidden = true)
            }
            Card(Modifier.weight(1f).fillMaxHeight()) {
                Title("정산 받을 계좌", 28)
                Body("동승자 페이지에 계좌번호 복사 버튼으로 보여요. 송금은 하지 않아요")
                Spacer(Modifier.height(20.dp))
                Field("은행", bankName, { bankName = it })
                Spacer(Modifier.height(12.dp))
                Field("계좌번호", account, { account = it })
                Spacer(Modifier.height(12.dp))
                Field("예금주", holder, { holder = it })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(consent, { consent = it })
                    Body("[필수] 계좌번호를 동승자에게 보여 주는 데 동의해요", Ink, 17)
                }
            }
        }
        if (note.isNotEmpty()) Body(note, Orange)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Btn("뒤로", Modifier.weight(1f), primary = false, onClick = onBack)
            Btn("저장하기", Modifier.weight(1f), enabled = name.isNotBlank()) {
                scope.launch {
                    try {
                        rpc.call("set_driver_info", args("p_vehicle_secret" to secret, "p_name" to name, "p_phone4" to phone4.ifEmpty { null }))
                        if (account.isNotBlank()) {
                            rpc.call("set_bank_account", args("p_vehicle_secret" to secret, "p_consent" to consent,
                                "p_bank_name" to bankName, "p_bank_account" to account, "p_account_holder" to holder))
                        }
                        onBack()
                    } catch (e: RpcException) {
                        note = "저장 실패: ${e.code}"
                    }
                }
            }
        }
    }
}

// ---- L1 new trip ----------------------------------------------------------------------------------------

private data class RuleOption(val title: String, val sub: String, val rule: String)

private val rules = listOf(
    RuleOption("공정 정산", "돌아간 만큼 더 내요", "shapley"),
    RuleOption("탄 거리 비례", "각자 탄 km만큼 내요", "distance"),
    RuleOption("N분의 1", "모두 똑같이 내요", "equal")
)


@Composable
private fun HomeScreen(
    config: Config, rpc: Rpc, onStart: () -> Unit, onDriver: () -> Unit, onFolders: () -> Unit,
    onSettings: () -> Unit, onMessage: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("MT 귀갓길") }
    var seats by remember { mutableIntStateOf(3) }
    var rule by remember { mutableIntStateOf(0) }
    var includeDriver by remember { mutableStateOf(true) }
    var dest by remember { mutableStateOf<Place?>(null) }
    var picking by remember { mutableStateOf(false) }
    var consent by remember { mutableStateOf(true) }
    val bookmarks by TripEngine.naviBookmarks.collectAsState()
    val recents by TripEngine.naviRecents.collectAsState()
    // the navi's own list; only when it has nothing does the demo home stand in
    val naviPlaces = (bookmarks + recents).distinctBy { "%.4f,%.4f".format(java.util.Locale.US, it.place.lat, it.place.lng) }
    val choices = naviPlaces.ifEmpty { listOf(kr.fairpool.car.trip.NaviPlace(DemoPlaces.home, "시연용")) }
    LaunchedEffect(Unit) { TripEngine.navi?.requestPlaces() }
    // until the driver picks one (or a favourite sets it), follow the navi list as it arrives
    var destAuto by remember { mutableStateOf(true) }
    LaunchedEffect(choices) { if (dest == null || destAuto) dest = choices.firstOrNull()?.place }
    var busy by remember { mutableStateOf(false) }
    var carpools by remember { mutableStateOf(JSONArray()) }
    var prepared by remember { mutableStateOf(JSONArray()) }
    val secret = config.vehicleSecret
    LaunchedEffect(Unit) {
        runCatching {
            val v = rpc.call("get_vehicle_trips", args("p_vehicle_secret" to secret))
            carpools = v.optJSONArray("carpools") ?: JSONArray()
            prepared = v.optJSONArray("trips") ?: JSONArray()
        }
        runCatching {
            rpc.call("get_driver_info", args("p_vehicle_secret" to secret)).optString("name")
                .takeIf { it.isNotBlank() }?.let { TripEngine.driverName = it }
        }
    }
    /** A trip made earlier (on the phone or in the car) and still preparing: pick it up with the car secret. */
    fun resume(code: String) {
        scope.launch {
            try {
                val t = rpc.call("get_trip_admin", args("p_code" to code, "p_admin_secret" to secret))
                val origin = Place.from(t.optJSONObject("origin")) ?: currentPlace()
                val destination = Place.from(t.optJSONObject("destination")) ?: dest ?: DemoPlaces.home
                TripEngine.startInvite(code, secret ?: "", t.optString("title"), origin, destination, t.optInt("seats", 3),
                    t.optBoolean("include_driver", true), t.optString("rule", "shapley"))
                TripEngine.applyTripAdmin(t)
                onStart()
            } catch (e: Exception) {
                onMessage("운행을 불러오지 못했어요: ${e.message}")
            }
        }
    }
    /** A saved carpool (즐겨찾기): same name, destination, rule and size; members join again by QR. */
    fun fromCarpool(code: String, name: String) {
        title = name
        scope.launch {
            runCatching { rpc.call("get_carpool_admin", args("p_carpool_code" to code, "p_secret" to secret)) }
                .onSuccess { c ->
                    Place.from(c.optJSONObject("destination"))?.let { dest = it; destAuto = false }
                    seats = c.optInt("seats", seats).coerceIn(1, SeatSlot.entries.size)
                    rules.indexOfFirst { it.rule == c.optString("rule") }.takeIf { it >= 0 }?.let { rule = it }
                    includeDriver = c.optBoolean("include_driver", true)
                }
                .onFailure { onMessage("즐겨찾기를 불러오지 못했어요: ${it.message}") }
        }
    }
    if (picking) {
        DestinationPicker(bookmarks, recents, dest, onPick = { dest = it; destAuto = false; picking = false }, onClose = { picking = false })
    }
    Column(Modifier.fillMaxSize()) {
        Header("주차 중${if (config.demo) " (데모)" else ""}", Green, onSettings)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1.1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                Title("새 운행을 만들어요", 32)
                Body("탈 사람 수와 정산 규칙을 정해 두면 도착 후 금액은 차가 계산해요")
                Spacer(Modifier.height(20.dp))
                Card(Modifier.fillMaxWidth(), 22) {
                    Body("운행 이름", size = 16)
                    androidx.compose.foundation.text.BasicTextField(title, { title = it },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink))
                }
                Spacer(Modifier.height(14.dp))
                Card(Modifier.fillMaxWidth(), 22) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("함께 탈 사람 (운전자 빼고)", fontSize = 19.sp, color = Ink)
                            Body("QR로 들어온 사람이 직접 입력해요", size = 16)
                        }
                        Stepper("-") { if (seats > 1) seats-- }
                        Text("${seats}명", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp))
                        Stepper("+") { if (seats < SeatSlot.entries.size) seats++ }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Body("정산 규칙", Ink, 18)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    rules.forEachIndexed { i, r -> Chip(r.title, rule == i, Modifier.weight(1f), r.sub) { rule = i } }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Chip("운전자 포함", includeDriver, Modifier.weight(1f), "운전자 몫도 나눠 계산해요") { includeDriver = true }
                    Chip("운전자 제외", !includeDriver, Modifier.weight(1f), "동승자끼리만 나눠 내요") { includeDriver = false }
                }
                Spacer(Modifier.height(18.dp))
                Card(Modifier.fillMaxWidth().clickable { picking = true }, 22) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Body("도착지", size = 16)
                            Text(dest?.label ?: "내비에서 고르기", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink)
                            Body(dest?.address?.takeIf { it.isNotEmpty() && it != dest?.label } ?: "출발지는 출발할 때 차 위치로 기록해요", size = 15)
                        }
                        Text("바꾸기 ›", fontSize = 19.sp, color = Blue)
                    }
                }
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(consent, { consent = it })
                    Column {
                        Text("[필수] 위치 공유와 개인정보 처리에 동의해요", fontSize = 18.sp, color = Ink)
                        Body("픽업하는 동안만 차 위치를 동승자와 공유해요", size = 15)
                    }
                }
            }
            Column(Modifier.weight(0.9f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (prepared.length() > 0) {
                    Card(Modifier.fillMaxWidth(), 22) {
                        Text("준비 중인 운행", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
                        Spacer(Modifier.height(8.dp))
                        for (i in 0 until minOf(2, prepared.length())) {
                            val t = prepared.getJSONObject(i)
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(BlueSoft)
                                .clickable { resume(t.optString("code")) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(t.optString("title"), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                                    Body("${t.optInt("passengers")}명 참여, 이어서 진행", Blue, 15)
                                }
                                Text("›", fontSize = 30.sp, color = Blue)
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
                Card(Modifier.fillMaxWidth(), 22) {
                    Text("지난 운행 다시 만들기", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Spacer(Modifier.height(8.dp))
                    if (carpools.length() == 0) Body("즐겨찾기한 카풀이 아직 없어요", size = 17)
                    for (i in 0 until minOf(3, carpools.length())) {
                        val c = carpools.getJSONObject(i)
                        Row(Modifier.fillMaxWidth().clickable { fromCarpool(c.optString("code"), c.optString("title")) }.padding(vertical = 10.dp)) {
                            Column {
                                Text(c.optString("title"), fontSize = 19.sp, color = Ink)
                                Body("함께 탄 사람 ${c.optInt("members", c.optJSONArray("members")?.length() ?: 0)}명", size = 15)
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Btn("카풀 폴더", Modifier.weight(1f), primary = false, onClick = onFolders)
                    Btn("운전자 정보", Modifier.weight(1f), primary = false, onClick = onDriver)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Btn(if (busy) "만드는 중..." else "초대 QR 만들기", Modifier.fillMaxWidth(), enabled = consent && !busy && title.isNotBlank() && dest != null) {
            busy = true
            scope.launch {
                val origin = currentPlace()
                val destination = dest ?: return@launch
                val r = rules[rule]
                try {
                    val res = rpc.call("create_trip", args(
                        "p_title" to title, "p_origin" to origin.json(), "p_destination" to destination.json(),
                        "p_include_driver" to includeDriver, "p_seats" to seats, "p_rule" to r.rule,
                        "p_vehicle_secret" to secret))
                    TripEngine.startInvite(res.optString("code"), res.optString("admin_secret"), title, origin, destination, seats, includeDriver, r.rule)
                    onStart()
                } catch (e: Exception) {
                    onMessage("운행을 만들지 못했어요: ${e.message}")
                }
                busy = false
            }
        }
    }
}

/** Where the car is now (Pleos FusedLocation); the trip's start is recorded from it. */
private fun currentPlace(): Place =
    TripEngine.state.value.fix?.let { (lat, lng) -> Place("출발 위치", lat, lng) } ?: DemoPlaces.lodge

/** L1-10 도착지 고르기: the navi's bookmarks or recent destinations (two tabs), one place, no typing. */
@Composable
private fun DestinationPicker(bookmarks: List<kr.fairpool.car.trip.NaviPlace>, recents: List<kr.fairpool.car.trip.NaviPlace>,
                              selected: Place?, onPick: (Place) -> Unit, onClose: () -> Unit) {
    val fallback = bookmarks.isEmpty() && recents.isEmpty()
    var tab by remember { mutableIntStateOf(if (bookmarks.isEmpty() && recents.isNotEmpty()) 1 else 0) }
    var picked by remember { mutableStateOf(selected) }
    val list = when {
        fallback -> listOf(kr.fairpool.car.trip.NaviPlace(DemoPlaces.home, "시연용"))
        tab == 0 -> bookmarks
        else -> recents
    }
    Dialog(onDismissRequest = onClose) {
        Card(Modifier.width(760.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Title("도착지 고르기", 28)
                    Body(picked?.label?.let { "고른 도착지: $it" } ?: "운전자의 최종 목적지를 하나 골라요", size = 16)
                }
                Chip("내비 즐겨찾기", tab == 0) { tab = 0 }
                Spacer(Modifier.width(8.dp))
                Chip("최근 목적지", tab == 1) { tab = 1 }
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.height(440.dp).verticalScroll(rememberScrollState())) {
                if (fallback) Body("내비에 즐겨찾기와 최근 목적지가 없어 시연용 장소를 보여 줘요", Orange, 15)
                else if (list.isEmpty()) Body(if (tab == 0) "내비에 즐겨찾기가 없어요" else "최근 목적지가 없어요", size = 16)
                for (c in list) {
                    val on = picked?.let { it.label == c.place.label && it.distanceKm(c.place) < 0.05 } == true
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (on) BlueSoft else Color.Transparent)
                        .clickable { picked = c.place }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(c.kind, fontSize = 16.sp, color = Blue, modifier = Modifier.width(80.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.place.label, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                            if (c.place.address.isNotEmpty() && c.place.address != c.place.label) Body(c.place.address, size = 15)
                        }
                        if (on) Text("✓", fontSize = 22.sp, color = Blue)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Btn("취소", Modifier.weight(1f), primary = false, onClick = onClose)
                Btn("고르기", Modifier.weight(1f), enabled = picked != null) { picked?.let(onPick) }
            }
        }
    }
}

@Composable
private fun Stepper(sign: String, onClick: () -> Unit) {
    Box(Modifier.size(64.dp).clip(CircleShape).background(if (sign == "+") BlueSoft else Bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        Text(sign, fontSize = 30.sp, color = if (sign == "+") Blue else Ink)
    }
}

// ---- L2 invite QR ---------------------------------------------------------------------------------------

@Composable
private fun InviteScreen(config: Config, rpc: Rpc, onNext: () -> Unit, onMessage: (String) -> Unit) {
    val trip by TripEngine.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(trip.code) {
        while (true) {
            runCatching { TripEngine.applyTripAdmin(rpc.call("get_trip_admin", args("p_code" to trip.code, "p_admin_secret" to trip.adminSecret))) }
            delay(3000)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Header("주차 중")
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Card(Modifier.weight(0.8f).fillMaxHeight()) {
                Spacer(Modifier.weight(1f))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Qr("${config.siteUrl}/t/${trip.code}", 340) }
                Spacer(Modifier.height(24.dp))
                Text("휴대폰 카메라로 찍으면\n초대 링크를 받아요", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Body("운행 코드 ${trip.code}", size = 17)
                Spacer(Modifier.weight(1f))
            }
            Column(Modifier.weight(1.2f).fillMaxHeight()) {
                Body(trip.title, Blue, 18)
                Title("${trip.seats}명 중 ${trip.passengers.size}명이 들어왔어요", 30)
                Spacer(Modifier.height(16.dp))
                Card(Modifier.fillMaxWidth(), 22) {
                    for (p in trip.passengers) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Initial(p.name)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.name, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                                Body("${p.pickup?.label ?: "탈 곳 미입력"} → ${p.dropoff?.area?.ifEmpty { p.dropoff.label } ?: "내릴 곳 미입력"}", size = 16)
                            }
                            Text("입력 완료", fontSize = 16.sp, color = Blue, modifier = Modifier.clip(RoundedCornerShape(50)).background(BlueSoft).padding(horizontal = 14.dp, vertical = 6.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("✕", fontSize = 22.sp, color = Sub, modifier = Modifier.clickable {
                                scope.launch {
                                    runCatching { TripEngine.applyTripAdmin(rpc.call("remove_passenger", args("p_code" to trip.code, "p_admin_secret" to trip.adminSecret, "p_passenger_id" to p.id))) }
                                }
                            }.padding(8.dp))
                        }
                    }
                    for (i in trip.passengers.size until trip.seats) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(52.dp).clip(CircleShape).border(2.dp, Line, CircleShape), contentAlignment = Alignment.Center) {
                                Text("${i + 1}", fontSize = 19.sp, color = Sub)
                            }
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text("${i + 1}번째 자리", fontSize = 21.sp, color = Ink)
                                Body("아직 QR로 들어오지 않았어요", size = 16)
                            }
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Btn("내 휴대폰으로 보내기", Modifier.weight(1f), primary = false) {
                        scope.launch {
                            runCatching { rpc.call("queue_trip_share", args("p_code" to trip.code, "p_admin_secret" to trip.adminSecret)) }
                                .onSuccess { onMessage("운전자 휴대폰의 운행 링크에 초대 카드를 보냈어요") }
                                .onFailure { onMessage("보내지 못했어요: ${it.message}") }
                        }
                    }
                    Btn("경로 확인하기", Modifier.weight(1f), enabled = trip.passengers.isNotEmpty(), onClick = onNext)
                }
            }
        }
    }
}

// ---- L3 seats and route ---------------------------------------------------------------------------------

@Composable
private fun SeatsScreen(onDepart: () -> Unit) {
    val trip by TripEngine.state.collectAsState()
    LaunchedEffect(Unit) { TripEngine.autoMatchSeatsAtStart() }
    Column(Modifier.fillMaxSize()) {
        Header("탑승 ${trip.passengers.count { it.seat != null }}명 확인", Blue)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Card(Modifier.weight(0.85f).fillMaxHeight()) {
                Body("좌석", size = 17)
                Title("${trip.passengers.count { it.seat != null }}명이 앉아 있어요", 28)
                Spacer(Modifier.height(16.dp))
                SeatMap(Modifier.weight(1f).fillMaxWidth())
                Body("좌석 센서와 안전벨트로 앉은 자리를 알아내 이름을 붙였어요. 틀리면 자리를 눌러 바꿔요.", size = 16)
            }
            Card(Modifier.weight(1.15f).fillMaxHeight()) {
                Body("들를 곳 순서", size = 17)
                Title("${trip.plan.size}곳을 들러 ${trip.destination?.label ?: ""}까지", 26)
                Body("화살표로 순서를 바꿔요", size = 16)
                Spacer(Modifier.height(12.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    StopRow("출발", trip.origin?.label ?: "", "${trip.passengers.filter { p -> p.pickup == null || p.pickup.label == trip.origin?.label }.joinToString(", ") { it.name }} 탑승", Sub, null)
                    trip.plan.forEachIndexed { i, st ->
                        val (label, color) = when (st.kind) {
                            "pickup" -> "${st.name} 태우기" to Green
                            "dropoff" -> "${st.name} 내리기" to Blue
                            else -> "도착" to Ink
                        }
                        StopRow(label, st.place.label, st.place.area, color, if (st.kind == "end") null else i)
                    }
                }
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(BlueSoft).padding(16.dp)) {
                    Body("태우기는 그 사람을 내리기 전에만 둘 수 있어요. 내비 경유지는 최대 2곳이라 도착할 때마다 다음 곳을 이어서 안내해요.", Blue, 15)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Btn("경로 보내고 출발", Modifier.fillMaxWidth(), onClick = onDepart)
    }
}

@Composable
private fun StopRow(title: String, place: String, sub: String, color: Color, index: Int?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(color.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(color))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("$title  $place", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            if (sub.isNotEmpty()) Body(sub, size = 15)
        }
        if (index != null) {
            Text("▲", fontSize = 20.sp, color = Sub, modifier = Modifier.clickable { TripEngine.movePlan(index, index - 1) }.padding(10.dp))
            Text("▼", fontSize = 20.sp, color = Sub, modifier = Modifier.clickable { TripEngine.movePlan(index, index + 1) }.padding(10.dp))
        }
    }
}

/** Top view seat diagram: driver fixed, the three passenger seats show who sits there (tap to cycle). */
@Composable
private fun SeatMap(modifier: Modifier) {
    val trip by TripEngine.state.collectAsState()
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(300.dp).clip(RoundedCornerShape(70.dp)).background(Color(0xFFE9ECF2)).padding(26.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                SeatBox("운전자", true, true) {}
                SeatCell(SeatSlot.FRONT_RIGHT)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                SeatCell(SeatSlot.REAR_LEFT)
                SeatCell(SeatSlot.REAR_RIGHT)
            }
        }
    }
}

@Composable
private fun RowScope.SeatCell(slot: SeatSlot) {
    val trip by TripEngine.state.collectAsState()
    val who = trip.passengers.firstOrNull { it.seat == slot && !it.alighted }
    val occupied = trip.seatOccupied[slot] == true
    // no seat sensor and no belt there: that seat is confirmed by hand
    val manual = slot !in trip.seatSensor
    SeatBox((who?.name ?: slot.label) + if (manual) "\n수동 확인" else "", occupied, who != null) {
        // tap: the next person in the list takes this seat, swapping with whoever had it
        val order = trip.passengers.filter { !it.alighted }
        if (order.isNotEmpty()) {
            val next = order[(order.indexOfFirst { it.seat == slot } + 1) % order.size]
            TripEngine.swapSeat(next.id, slot)
        }
    }
}

@Composable
private fun RowScope.SeatBox(label: String, occupied: Boolean, named: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.weight(1f).height(120.dp).clip(RoundedCornerShape(22.dp))
            .background(if (named) Blue else if (occupied) Color(0xFF9DBBF7) else CardBg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (named || occupied) Color.White else Sub, textAlign = TextAlign.Center)
    }
}

// ---- L4 driving + L5 stop card --------------------------------------------------------------------------

@Composable
private fun DrivingScreen(canAct: Boolean, onArrive: () -> Unit) {
    val trip by TripEngine.state.collectAsState()
    LaunchedEffect(trip.card, canAct) {
        val card = trip.card ?: return@LaunchedEffect
        if (card.confirm) {
            // a question: nothing is recorded until the driver answers, and only while stopped
            if (!canAct) return@LaunchedEffect
            Voice.say("${card.name} 님 ${if (card.boarding) "탔나요" else "내렸나요"}? 맞으면 네, 아니면 아니요라고 말해 주세요") {
                Voice.askConfirm { ok -> if (ok) TripEngine.confirmCard() else TripEngine.dismissCard() }
            }
            try {
                awaitCancellation()
            } finally {
                Voice.stopListening()
            }
        }
        // read the card once, then listen for "맞아" / "잘못 인식" while it is up and the car is stopped
        Voice.say("${card.name} 님 ${if (card.boarding) "탑승을" else "하차를"} 기록했어요") {
            if (canAct) Voice.askConfirm { ok -> if (ok) TripEngine.dismissCard() else TripEngine.undoCard() }
        }
        try {
            delay(9000)
            TripEngine.dismissCard()
        } finally {
            Voice.stopListening()
        }
    }
    Column(Modifier.fillMaxSize()) {
        Header(if (canAct) "정차 중" else "카풀 기록 중", if (canAct) Orange else Green)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1.2f).fillMaxHeight()) {
                val next = trip.nextStop
                Card(Modifier.fillMaxWidth()) {
                    Body(when (next?.kind) { "pickup" -> "${next.name} 태울 곳"; "dropoff" -> "${next.name} 내릴 곳"; else -> "도착지" }, size = 18)
                    Title(next?.place?.label ?: (trip.destination?.label ?: ""), 40)
                    val ahead = trip.ahead.firstOrNull()
                    Body(
                        when {
                            trip.atStop -> "도착했어요, ${next?.place?.area ?: ""}"
                            ahead != null -> "%.1fkm, 약 %d분, %s".format(ahead.first, ahead.second, next?.place?.area ?: "")
                            else -> next?.place?.area ?: ""
                        },
                        if (trip.atStop) Green else Sub, 18
                    )
                }
                Spacer(Modifier.height(16.dp))
                Card(Modifier.fillMaxWidth().weight(1f), 14) {
                  Row(Modifier.fillMaxSize()) {
                    CourseMap(trip.origin, trip.plan, trip.passengers,
                        Modifier.weight(1.45f).fillMaxHeight().clip(RoundedCornerShape(16.dp)), car = trip.fix)
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f).padding(top = 8.dp).verticalScroll(rememberScrollState())) {
                        Body("코스", Ink, 19)
                        Spacer(Modifier.height(6.dp))
                        for (st in trip.plan) {
                            val color = if (st.done) Sub else when (st.kind) { "pickup" -> Green; "dropoff" -> Blue; else -> Ink }
                            val label = when (st.kind) { "pickup" -> "${st.name} 태우기"; "dropoff" -> "${st.name} 내리기"; else -> "도착" }
                            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (st.done) "✓" else "●", color = color, fontSize = 20.sp, modifier = Modifier.width(32.dp))
                                Column {
                                    Text(label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = if (st.done) Sub else Ink)
                                    Text(st.place.label, fontSize = 15.sp, color = Sub)
                                }
                            }
                        }
                    }
                  }
                }
            }
            Column(Modifier.weight(0.8f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Stat("탑승", "${trip.aboard.size}명", Modifier.weight(1f))
                    Stat("주행", "%.1fkm".format(trip.drivenKm), Modifier.weight(1f))
                    Stat("연비", (trip.tripKmL ?: trip.efficiencyKmL)?.let { "%.1fkm/L".format(it) } ?: "-", Modifier.weight(1f))
                }
                Card(Modifier.fillMaxWidth().weight(1f), 22) {
                    Body(if (canAct) "수동 기록 (센서가 놓쳤을 때)" else "탄 사람", Ink, 17)
                    Spacer(Modifier.height(6.dp))
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        for (p in trip.passengers) {
                            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(p.name, fontSize = 19.sp, color = Ink, modifier = Modifier.width(80.dp))
                                Body(when { p.alighted -> "내림"; p.boarded -> "타고 있음"; else -> "탈 예정" }, size = 16)
                                Spacer(Modifier.weight(1f))
                                // recording by hand only while stopped
                                if (canAct && !p.boarded) MiniBtn("탑승") { TripEngine.board(p.id, p.seat, unchecked = false) }
                                if (canAct && p.boarded && !p.alighted) MiniBtn("하차") { TripEngine.alight(p.id) }
                            }
                        }
                    }
                }
                Body(
                    if (canAct) "기어 ${trip.gear}, 정차 중이라 기록을 고칠 수 있어요"
                    else "주행 중에는 화면을 조작하지 않아도 돼요. 탑승, 하차, 도착은 정차하면 보여요",
                    size = 15
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Btn("내비에서 보기", Modifier.weight(1f), primary = false) { TripEngine.navi?.showInNavi() }
                    if (canAct) Btn("도착, 정산하기", Modifier.weight(1f), onClick = onArrive)
                }
            }
        }
    }
    trip.card?.let { card ->
        val tint = if (card.confirm) Orange else Green
        Box(Modifier.fillMaxSize().padding(top = 80.dp), contentAlignment = Alignment.TopEnd) {
            Row(
                Modifier.width(680.dp).clip(RoundedCornerShape(24.dp)).background(CardBg)
                    .border(3.dp, tint, RoundedCornerShape(24.dp)).padding(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(56.dp).clip(CircleShape).background(tint), contentAlignment = Alignment.Center) {
                    Text(if (card.confirm) "?" else "✓", color = Color.White, fontSize = 26.sp)
                }
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    if (card.confirm) {
                        Text("${card.name}님이 ${if (card.boarding) "탔나요?" else "내렸나요?"}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
                        val seat = card.slot?.label ?: "자리"
                        Body(
                            if (card.boarding) "${seat}에 누가 앉았는데 탈 곳이 아니에요"
                            else "${seat}이 비었는데 내릴 곳이 아니에요. 잠깐 내린 거면 '아니요'를 눌러요",
                            size = 16
                        )
                        if (!canAct) Body("정차하면 확인할 수 있어요", Orange, 15)
                    } else {
                        Text("${card.name}님 ${if (card.boarding) "탑승을" else "하차를"} 기록했어요", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
                        val at = trip.passengers.firstOrNull { it.id == card.passengerId }
                            ?.let { if (card.boarding) it.boardPlace else it.alightPlace }?.label ?: ""
                        Body(if (trip.drivenKm > 0.05) "$at, %.1fkm 지점".format(trip.drivenKm) else at, size = 16)
                    }
                }
                if (canAct) {
                    if (card.confirm) {
                        MiniBtn(if (card.boarding) "탔어요" else "내렸어요") { TripEngine.confirmCard() }
                        MiniBtn("아니요") { TripEngine.dismissCard() }
                    } else {
                        MiniBtn("잘못 인식") { TripEngine.undoCard() }
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Card(modifier, 20) {
        Body(label, size = 16)
        Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink)
    }
}

@Composable
private fun MiniBtn(text: String, onClick: () -> Unit) {
    Box(Modifier.padding(start = 8.dp).height(56.dp).clip(RoundedCornerShape(14.dp)).border(2.dp, Line, RoundedCornerShape(14.dp))
        .clickable(onClick = onClick).padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink)
    }
}

// ---- L6 settlement --------------------------------------------------------------------------------------

@Composable
private fun SettleScreen(rpc: Rpc, onDone: () -> Unit, onMessage: (String) -> Unit) {
    val trip by TripEngine.state.collectAsState()
    val scope = rememberCoroutineScope()
    var folders by remember { mutableStateOf(JSONArray()) }
    var chosen by remember { mutableStateOf<String?>(null) }
    var newFolder by remember { mutableStateOf("") }
    var tollEdit by remember { mutableStateOf<String?>(null) }
    var showGroups by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    var rerouting by remember { mutableStateOf(false) }
    var postNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val a = TripEngine.computeSettlement()
        if (a != null) {
            val riders = a.shares.count { !it.rider.isDriver }
            Voice.say("오늘 총비용 ${NumberFormat.getInstance().format(a.total)}원, 동승자 ${riders}명에게 보낼 준비가 됐어요")
        }
        // nothing goes to the passengers yet: the driver checks (and fixes the toll), then 확정 posts it once
        runCatching { folders = rpc.call("get_trip_admin", args("p_code" to trip.code, "p_admin_secret" to trip.adminSecret)).optJSONArray("folders") ?: JSONArray() }
    }
    val a = trip.allocation
    if (showGroups) {
        Dialog(onDismissRequest = { showGroups = false }) {
            Card(Modifier.width(640.dp)) {
                Title("조합별 거리", 26)
                val from = when (trip.distanceSource) {
                    "saved" -> "출발 때 받아 둔 거리표"
                    "segments" -> "길찾기 없이 구간별로 나눔"
                    else -> "도착 후 길찾기로 받은 도로 거리"
                }
                Body("$from, 돌아가는 조합은 지나가는 경로로 맞춤", size = 15)
                Spacer(Modifier.height(12.dp))
                Column(Modifier.height(520.dp).verticalScroll(rememberScrollState())) {
                    for ((names, km) in trip.coalitions.sortedBy { it.first.size }) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                            Text(names.joinToString(" + "), fontSize = 18.sp, color = Ink, modifier = Modifier.weight(1f))
                            Text("%.1fkm".format(km), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                        }
                    }
                    if (trip.coalitions.isEmpty()) Body("이번 정산은 조합 거리를 쓰지 않았어요", size = 16)
                }
                Spacer(Modifier.height(12.dp))
                Btn("닫기", Modifier.fillMaxWidth(), primary = false) { showGroups = false }
            }
        }
    }
    tollEdit?.let { value ->
        Dialog(onDismissRequest = { tollEdit = null }) {
            Card(Modifier.width(560.dp)) {
                Title("통행료 고치기", 26)
                Body("내비는 남은 길의 통행료만 알려 줘요. 하이패스 기록을 보고 실제 낸 금액으로 고쳐요.", size = 16)
                Spacer(Modifier.height(16.dp))
                val apply = {
                    val won = value.toLongOrNull() ?: 0
                    tollEdit = null
                    TripEngine.setToll(won)
                    scope.launch {
                        TripEngine.computeSettlement()?.let {
                            Voice.say("통행료를 반영해 총비용은 ${NumberFormat.getInstance().format(it.total)}원이에요")
                        }
                    }
                }
                Field("통행료 (원)", value, { tollEdit = it.filter(Char::isDigit) }, onDone = { apply() })
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Btn("취소", Modifier.weight(1f), primary = false) { tollEdit = null }
                    Btn("다시 나누기", Modifier.weight(1f)) { apply() }
                }
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Header("도착, 주차 중")
        if (a == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Title("함께 탄 거리로 나누는 중이에요", 30) }
            return
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(0.9f).fillMaxHeight()) {
                Body("${trip.title} 정산", Blue, 18)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(NumberFormat.getInstance().format(a.total), fontSize = 64.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Text(" 원", fontSize = 28.sp, color = Sub, modifier = Modifier.padding(bottom = 10.dp))
                }
                Body(when (a.method) {
                    "shapley" -> "공정 정산: 함께 타서 아낀 돈을 각자 만든 거리만큼 나눴어요"
                    "distance" -> "탄 거리 비례: 각자 탄 km만큼 나눴어요"
                    "equal" -> if (trip.includeDriver) "N분의 1: 운전자까지 모두 똑같이 나눴어요" else "N분의 1: 동승자끼리 똑같이 나눴어요"
                    else -> "공정 정산: 길찾기가 안 돼 구간별로 탄 사람끼리 나눴어요"
                } + if (trip.includeDriver) "" else " (운전자 제외)", size = 18)
                if (a.provisional) {
                    Spacer(Modifier.height(10.dp))
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFFFFF4E5)).padding(14.dp)) {
                        Text("임시 금액이에요", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Orange)
                        Body(
                            if (trip.distanceSource == "saved") "출발 때 받아 둔 거리표로 계산했어요. 도착 후 길찾기가 되면 금액이 바뀔 수 있어요"
                            else "도착 후 길찾기를 못 해 혼자 갈 때 금액은 어림값이에요. 길찾기가 되면 다시 계산해 올려요",
                            size = 15
                        )
                        Spacer(Modifier.height(8.dp))
                        MiniBtn(if (rerouting) "길찾는 중..." else "길찾기 다시 하기") {
                            if (rerouting) return@MiniBtn
                            rerouting = true
                            scope.launch {
                                val again = TripEngine.computeSettlement(forceRoute = true)
                                rerouting = false
                                onMessage(if (again?.provisional == false) "길찾기 결과로 다시 계산했어요" else "아직 길찾기가 안 돼요")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Stat("기름값", won(a.fuelAmount), Modifier.weight(1f))
                    Stat("통행료 (눌러서 고치기)", won(a.toll), Modifier.weight(1f).clickable { tollEdit = a.toll.toString() })
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Stat("총 거리 (눌러서 조합별 거리)", "%.1fkm".format(trip.settlementKm), Modifier.weight(1f).clickable { showGroups = true })
                    val economyFrom = when (trip.efficiencySource) {
                        "trip" -> "주행 중 측정"
                        "car" -> "차가 알려 준 값"
                        else -> "기본값, 설정에서 바꿔요"
                    }
                    Stat("연비 ($economyFrom)", "%.1fkm/L".format(trip.settlementEfficiency), Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                Body(
                    postNote ?: if (trip.settlementPosted) "동승자 페이지에 정산을 올렸어요" else "확정하면 동승자 페이지에 올라가요",
                    if (postNote != null) Orange else if (trip.settlementPosted) Green else Sub, 17
                )
                Spacer(Modifier.height(12.dp))
                // only the people who actually rode, in colour
                val rode = trip.passengers.filter { it.boarded }
                CourseMap(trip.origin,
                    trip.plan.filter { st -> st.passengerId == null || rode.any { it.id == st.passengerId } }.map { it.copy(done = false) },
                    rode,
                    Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(20.dp)))
            }
            Column(Modifier.weight(1.1f).fillMaxHeight()) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    a.shares.forEachIndexed { i, s ->
                        val p = trip.passengers.firstOrNull { it.id == s.rider.id }
                        val ridden = trip.riddenKm.getOrNull(i) ?: 0.0
                        val capped = i in a.capped
                        val diff = a.diffFromFair(i)
                        val pays = trip.includeDriver || !s.rider.isDriver
                        Card(Modifier.fillMaxWidth(), 22) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Initial(s.rider.name, if (s.rider.isDriver) Sub else Blue, if (s.rider.isDriver) Bg else BlueSoft)
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(s.rider.name, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                                    val route = "${p?.boardPlace?.label ?: ""} → ${p?.alightPlace?.area?.ifEmpty { p.alightPlace.label } ?: ""}"
                                    val what = when (a.method) {
                                        "equal" -> "N분의 1"
                                        "distance" -> "탄 거리 %.1fkm".format(ridden)
                                        else -> "몫 %.1fkm".format(s.shareKm)
                                    }
                                    val alone = if (a.method == "equal" || s.rider.isDriver) ""
                                        else ", 혼자면 ${if (a.provisional) "약 " else ""}${won(s.alone)}"
                                    Body(
                                        when {
                                            !pays -> "운전자, 내지 않아요"
                                            s.rider.isDriver -> "운전자, $what"
                                            else -> "$route, $what$alone"
                                        },
                                        size = 15
                                    )
                                    if (diff != null && pays) {
                                        Body(
                                            when {
                                                diff > 0 -> "공정 정산보다 ${won(diff)} 더 내요"
                                                diff < 0 -> "공정 정산보다 ${won(-diff)} 덜 내요"
                                                else -> "공정 정산과 같아요"
                                            },
                                            if (diff == 0L) Sub else Blue, 15
                                        )
                                    }
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(won(s.amount), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = if (s.rider.isDriver) Sub else Ink)
                                    if (capped) {
                                        Text("혼자 갈 때까지만", fontSize = 13.sp, color = Orange,
                                            modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xFFFFF4E5))
                                                .padding(horizontal = 10.dp, vertical = 3.dp))
                                    }
                                    if (a.provisional) Text("임시", fontSize = 13.sp, color = Orange)
                                }
                            }
                        }
                    }
                    if (a.driverAbsorbed > 0) {
                        Card(Modifier.fillMaxWidth(), 22) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("운전자 부담", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                                    Body("모두 혼자 갈 때 금액까지만 내서 남은 금액이에요", size = 15)
                                }
                                Text(won(a.driverAbsorbed), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Sub)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Body("정산 방법", Ink, 17)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    Chip("바로 정산", chosen == "direct", sub = "이번 운행만") { chosen = "direct" }
                    for (i in 0 until minOf(2, folders.length())) {
                        val f = folders.getJSONObject(i)
                        Chip(f.optString("name"), chosen == f.optString("code"), sub = "${f.optInt("trips")}회 쌓임") { chosen = f.optString("code") }
                    }
                    Chip("+ 새 카풀 폴더", chosen == "new", sub = trip.title) { chosen = "new"; newFolder = trip.title }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Btn("즐겨찾기 추가", Modifier.weight(1f), primary = false) {
                        scope.launch {
                            runCatching { rpc.call("save_carpool", args("p_code" to trip.code, "p_admin_secret" to trip.adminSecret)) }
                                .onSuccess { onMessage("즐겨찾기에 추가했어요") }.onFailure { onMessage("추가 실패: ${it.message}") }
                        }
                    }
                    Btn("내 휴대폰으로 보내기", Modifier.weight(1f), primary = false, enabled = chosen != null) {
                        scope.launch {
                            runCatching { rpc.call("queue_trip_share", args("p_code" to trip.code, "p_admin_secret" to trip.adminSecret)) }
                                .onSuccess { onMessage("운전자 휴대폰에 정산 카드를 보냈어요") }.onFailure { onMessage("보내지 못했어요: ${it.message}") }
                        }
                    }
                    Btn(if (confirming) "올리는 중..." else "확정하고 완료", Modifier.weight(1f), enabled = chosen != null && !confirming) {
                        confirming = true
                        scope.launch {
                            val c = chosen ?: return@launch
                            // one post_settlement, with the toll the driver checked; only a missing network is retried
                            var result: PostResult = if (trip.settlementPosted) PostResult.Ok else TripEngine.postSettlement()
                            var tries = 0
                            while (result == PostResult.Offline && tries < 20) {
                                tries++
                                postNote = "네트워크를 기다리는 중이에요 ($tries/20)"
                                delay(3000)
                                result = TripEngine.postSettlement()
                            }
                            when (result) {
                                is PostResult.Refused -> {
                                    val note = TripEngine.refusalMessage(result.code)
                                    postNote = note
                                    confirming = false
                                    onMessage(note)
                                    return@launch
                                }
                                PostResult.Offline -> {
                                    postNote = "네트워크에 연결되지 않아 올리지 못했어요. 연결되면 다시 눌러 주세요"
                                    confirming = false
                                    return@launch
                                }
                                PostResult.Ok -> postNote = null
                            }
                            val a2 = when (c) {
                                "direct" -> args("p_code" to trip.code, "p_admin_secret" to trip.adminSecret, "p_mode" to "direct")
                                "new" -> args("p_code" to trip.code, "p_admin_secret" to trip.adminSecret, "p_mode" to "folder", "p_new_folder" to newFolder.ifBlank { "카풀 폴더" })
                                else -> args("p_code" to trip.code, "p_admin_secret" to trip.adminSecret, "p_mode" to "folder", "p_folder_code" to c)
                            }
                            try {
                                rpc.call("choose_settlement", a2)
                                onMessage(if (c == "direct") "동승자 페이지에 계좌번호가 보여요" else "카풀 폴더에 쌓았어요")
                                onDone()
                            } catch (e: RpcException) {
                                confirming = false
                                postNote = "정산 방법을 저장하지 못했어요: ${TripEngine.refusalMessage(e.code)}"
                            } catch (e: Exception) {
                                confirming = false
                                postNote = "네트워크에 연결되지 않아 정산 방법을 저장하지 못했어요. 다시 눌러 주세요"
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---- L1-6 / L1-7 carpool folders ------------------------------------------------------------------------

@Composable
private fun FoldersScreen(config: Config, rpc: Rpc, onBack: () -> Unit, onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val secret = config.vehicleSecret
    var folders by remember { mutableStateOf(JSONArray()) }
    var open by remember { mutableStateOf<JSONObject?>(null) }
    var openCode by remember { mutableStateOf<String?>(null) }
    suspend fun load() {
        runCatching {
            val r = rpc.call("list_folders", args("p_secret" to secret))
            folders = r.optJSONArray("folders") ?: r.optJSONArray("items") ?: JSONArray()
        }
        openCode?.let { c -> runCatching { open = rpc.call("get_folder_admin", args("p_folder_code" to c, "p_secret" to secret)) } }
    }
    LaunchedEffect(openCode) { load() }
    Column(Modifier.fillMaxSize()) {
        Header("주차 중")
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Card(Modifier.weight(0.9f).fillMaxHeight()) {
                Title("카풀 폴더", 28)
                Body("같은 사람들과의 여러 운행을 모아 한 번에 정산해요", size = 17)
                Spacer(Modifier.height(16.dp))
                if (folders.length() == 0) Body("아직 폴더가 없어요. 도착 정산에서 \"새 카풀 폴더\"로 만들어요", size = 17)
                for (i in 0 until folders.length()) {
                    val f = folders.getJSONObject(i)
                    val code = f.optString("code")
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (code == openCode) BlueSoft else Color.Transparent)
                        .clickable { openCode = code }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.optString("name"), fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                            Body("${if (f.optString("status") == "requested") "정산 중" else "요청 전"}, ${f.optInt("trips")}회", size = 16)
                        }
                        Text(won(f.optLong("amount", f.optLong("receivable"))), fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Ink)
                    }
                }
            }
            Card(Modifier.weight(1.1f).fillMaxHeight()) {
                val f = open
                if (f == null) {
                    Body("폴더를 고르면 쌓인 운행과 사람별 합계가 보여요")
                } else {
                    val requested = f.optJSONObject("requested")
                    Title(f.optString("name"), 28)
                    Spacer(Modifier.height(12.dp))
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        if (requested != null) {
                            Body("정산 중: 받았으면 완료로 바꿔요", Blue, 17)
                            val pays = requested.optJSONArray("payments") ?: JSONArray()
                            for (i in 0 until pays.length()) {
                                val p = pays.getJSONObject(i)
                                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(p.optString("name"), fontSize = 20.sp, color = Ink, modifier = Modifier.weight(1f))
                                    Text(won(p.optLong("amount")), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
                                    MiniBtn(if (p.optBoolean("paid")) "완료됨 ✓" else "정산 완료") {
                                        scope.launch {
                                            runCatching { open = rpc.call("mark_batch_paid", args("p_folder_code" to openCode, "p_secret" to secret,
                                                "p_payment_id" to p.optString("id"), "p_paid" to !p.optBoolean("paid"))) }
                                            load()
                                        }
                                    }
                                }
                            }
                        }
                        val o = f.optJSONObject("open")
                        val people = o?.optJSONArray("people") ?: JSONArray()
                        if (people.length() > 0) {
                            Spacer(Modifier.height(12.dp))
                            Body("요청 전: 쌓인 금액", Ink, 17)
                            for (i in 0 until people.length()) {
                                val p = people.getJSONObject(i)
                                Row(Modifier.padding(vertical = 6.dp)) {
                                    Text(p.optString("name"), fontSize = 20.sp, color = Ink, modifier = Modifier.weight(1f))
                                    Text(won(p.optLong("amount")), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
                                }
                            }
                        }
                    }
                    if (f.optJSONObject("open")?.optJSONArray("people")?.length() ?: 0 > 0) {
                        Btn("합쳐서 정산하기", Modifier.fillMaxWidth()) {
                            scope.launch {
                                runCatching { open = rpc.call("request_batch", args("p_folder_code" to openCode, "p_secret" to secret)) }
                                    .onSuccess { onMessage("정산 요청을 만들었어요. 동승자 페이지에 금액이 보여요") }
                                    .onFailure { onMessage("요청 실패: ${it.message}") }
                                load()
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Btn("뒤로", Modifier.fillMaxWidth(), primary = false, onClick = onBack)
    }
}
