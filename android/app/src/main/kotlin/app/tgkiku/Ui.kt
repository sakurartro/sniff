package app.tgkiku

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import uniffi.tgkiku_ffi.Chat
import uniffi.tgkiku_ffi.TrackInfo

val Accent = Color(0xFFFA2D48)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme())
        darkColorScheme(primary = Accent, background = Color.Black, surface = Color(0xFF1C1C1E), onBackground = Color.White, onSurface = Color.White, onSurfaceVariant = Color(0xFF8E8E93), outlineVariant = Color(0x33FFFFFF))
    else
        lightColorScheme(primary = Accent, background = Color.White, surface = Color(0xFFF2F2F7), onBackground = Color.Black, onSurface = Color.Black, onSurfaceVariant = Color(0xFF8E8E93), outlineVariant = Color(0x1F000000))
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun Root(vm: Vm) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            when {
                !vm.ready -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = Accent) }
                !vm.authorized -> LoginScreen(vm)
                else -> MainScreen(vm)
            }
            vm.error?.let {
                Text(
                    it, color = Color.White, fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.TopCenter).systemBarsPadding().padding(12.dp)
                        .clip(RoundedCornerShape(12.dp)).background(Accent).clickable { vm.error = null }.padding(12.dp),
                )
            }
        }
    }
}

// ---------- login ----------

@Composable
fun LoginScreen(vm: Vm) {
    var phone by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(painterResource(R.drawable.logo), null, Modifier.size(112.dp).clip(RoundedCornerShape(26.dp)))
        Spacer(Modifier.height(16.dp))
        Text("tgkiku", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("Музыка из ваших чатов Telegram", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
        Spacer(Modifier.height(28.dp))
        when (vm.step) {
            Step.Phone -> {
                Field(phone, { phone = it }, "Телефон, например +79001234567", KeyboardType.Phone)
                Primary("Получить код", !vm.busy && phone.isNotBlank()) { vm.sendPhone(phone) }
                TextButton({ vm.startQr() }) { Text("Войти по QR / ссылке", color = Accent) }
            }
            Step.Code -> {
                Field(code, { code = it }, "Код из Telegram", KeyboardType.Number)
                Primary("Войти", !vm.busy && code.isNotBlank()) { vm.sendCode(code) }
            }
            Step.Password -> {
                Field(pass, { pass = it }, "Пароль 2FA", KeyboardType.Password, password = true)
                Primary("Продолжить", !vm.busy && pass.isNotEmpty()) { vm.sendPassword(pass) }
            }
            Step.Qr -> {
                val link = vm.qrLink
                if (link == null) CircularProgressIndicator(color = Accent) else {
                    Image(remember(link) { qrBitmap(link) }, null, Modifier.size(220.dp).clip(RoundedCornerShape(12.dp)), filterQuality = FilterQuality.None)
                    Text("Отсканируйте в Telegram: Настройки → Устройства", textAlign = TextAlign.Center, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                    Primary("Открыть в Telegram на этом телефоне", true) { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
                }
                TextButton({ vm.backToPhone() }) { Text("Назад", color = Accent) }
            }
        }
        if (vm.busy) LinearProgressIndicator(Modifier.padding(top = 12.dp).fillMaxWidth(), color = Accent)
    }
}

@Composable
fun Field(v: String, on: (String) -> Unit, hint: String, kb: KeyboardType, password: Boolean = false) {
    TextField(
        v, on, Modifier.fillMaxWidth().padding(bottom = 12.dp), singleLine = true, placeholder = { Text(hint) },
        shape = RoundedCornerShape(12.dp), keyboardOptions = KeyboardOptions(keyboardType = kb),
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface, cursorColor = Accent),
    )
}

@Composable
fun Primary(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick, Modifier.fillMaxWidth().height(50.dp), enabled = enabled, shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White)) {
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

fun qrBitmap(text: String): ImageBitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0) // size 0 = one pixel per module
    val px = IntArray(m.width * m.height) { if (m[it % m.width, it / m.width]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888).asImageBitmap()
}

// ---------- main ----------

@Composable
fun MainScreen(vm: Vm) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var full by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> TrackList(vm, "Медиатека", vm.tracks, "Пусто. Выберите чаты во вкладке «Чаты» и синхронизируйте.")
                    1 -> SearchTab(vm)
                    else -> ChatsTab(vm)
                }
            }
            if (vm.current != null) MiniPlayer(vm) { full = true }
            TabBar(tab) { tab = it }
        }
        AnimatedVisibility(full, enter = slideInVertically { it }, exit = slideOutVertically { it }) { NowPlaying(vm) { full = false } }
    }
    BackHandler(full) { full = false }
}

@Composable
fun TabBar(sel: Int, on: (Int) -> Unit) {
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().height(52.dp)) {
            listOf("Медиатека", "Поиск", "Чаты").forEachIndexed { i, s ->
                Box(Modifier.weight(1f).fillMaxHeight().clickable { on(i) }, Alignment.Center) {
                    Text(s, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (i == sel) Accent else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun LargeTitle(s: String) = Text(s, fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp))

@Composable
fun TrackList(vm: Vm, title: String, list: List<TrackInfo>, empty: String, header: @Composable () -> Unit = {}) {
    val state = rememberLazyListState()
    // warm covers for the rows just below the viewport so they are ready when scrolled to
    LaunchedEffect(list, state) {
        snapshotFlow { state.firstVisibleItemIndex }.collectLatest { first ->
            coroutineScope { for (t in list.drop((first - 2).coerceAtLeast(0)).take(25)) launch { Covers.file(t) } }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), state) {
        item { LargeTitle(title) }
        item { header() }
        if (list.isEmpty()) item { Text(empty, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp)) }
        itemsIndexed(list, key = { _, t -> t.documentId }) { i, t -> TrackRow(t, vm.current?.documentId == t.documentId) { vm.play(list, i) } }
    }
}

@Composable
fun TrackRow(t: TrackInfo, active: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Art(t, Modifier.size(48.dp), 6.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(t.title, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (active) Accent else MaterialTheme.colorScheme.onBackground)
            Text(t.performer.ifBlank { "Неизвестный исполнитель" }, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(fmt(t.duration.toLong() * 1000), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SearchTab(vm: Vm) {
    var q by rememberSaveable { mutableStateOf("") }
    TrackList(vm, "Поиск", vm.results, if (q.isBlank()) "Ищите по названию или исполнителю." else "Ничего не найдено.") {
        TextField(
            q, { q = it; vm.search(it) }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), singleLine = true,
            placeholder = { Text("Ваша медиатека") }, shape = RoundedCornerShape(12.dp),
            colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface, cursorColor = Accent),
        )
    }
}

@Composable
fun ChatsTab(vm: Vm) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { LargeTitle("Чаты") }
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ vm.refreshChats() }, enabled = !vm.busy, shape = RoundedCornerShape(12.dp)) { Text("Обновить список", color = Accent) }
                    Button({ vm.sync() }, enabled = !vm.busy, shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White)) { Text("Синхронизировать") }
                }
                vm.syncText?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)) }
                if (vm.busy) LinearProgressIndicator(Modifier.padding(top = 8.dp).fillMaxWidth(), color = Accent)
            }
        }
        itemsIndexed(vm.chats, key = { _, c -> c.id }) { _, c -> ChatRow(c) { vm.toggle(c) } }
    }
}

@Composable
fun ChatRow(c: Chat, on: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = on).padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(c.title, Modifier.weight(1f), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Switch(c.selected, { on() }, colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF34C759), checkedThumbColor = Color.White))
    }
}

// ---------- player ----------

@Composable
fun MiniPlayer(vm: Vm, open: () -> Unit) {
    val t = vm.current ?: return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface).clickable(onClick = open).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Art(t, Modifier.size(44.dp), 6.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(t.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (vm.loadingTrack) Text("Загрузка…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Ico(if (vm.playing) "pause" else "play", 28.dp, MaterialTheme.colorScheme.onSurface, Modifier.clickable { vm.toggle() })
        Spacer(Modifier.width(18.dp))
        Ico("next", 28.dp, MaterialTheme.colorScheme.onSurface, Modifier.clickable { vm.next() })
        Spacer(Modifier.width(6.dp))
    }
}

@Composable
fun NowPlaying(vm: Vm, close: () -> Unit) {
    val t = vm.current ?: return
    val dur = (t.duration.toLong() * 1000).coerceAtLeast(1)
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.padding(bottom = 24.dp).size(width = 40.dp, height = 5.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.outlineVariant).clickable(onClick = close))
        Art(t, Modifier.fillMaxWidth().aspectRatio(1f), 14.dp)
        Spacer(Modifier.height(28.dp))
        Text(t.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
        Text(t.performer.ifBlank { "Неизвестный исполнитель" }, fontSize = 18.sp, color = Accent, maxLines = 1, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Slider(
            vm.pos.coerceIn(0, dur).toFloat(), { vm.seek(it.toLong()) }, valueRange = 0f..dur.toFloat(),
            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.onBackground, activeTrackColor = MaterialTheme.colorScheme.onBackground, inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant),
        )
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            Text(fmt(vm.pos), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (vm.loadingTrack) "Загрузка…" else fmt(dur), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly, Alignment.CenterVertically) {
            Ico("prev", 44.dp, MaterialTheme.colorScheme.onBackground, Modifier.clickable { vm.prev() })
            Ico(if (vm.playing) "pause" else "play", 64.dp, MaterialTheme.colorScheme.onBackground, Modifier.clickable { vm.toggle() })
            Ico("next", 44.dp, MaterialTheme.colorScheme.onBackground, Modifier.clickable { vm.next() })
        }
    }
}

// ---------- bits ----------

/** Cover when one is found, else a gradient derived from the title. */
@Composable
fun Art(t: TrackInfo, modifier: Modifier, radius: Dp) {
    val cover by produceState<Bitmap?>(null, t.documentId) { value = Covers.bitmap(t) }
    val h = ((t.title + t.performer).hashCode().toUInt() % 360u).toFloat()
    cover?.let { Image(it.asImageBitmap(), null, modifier.clip(RoundedCornerShape(radius)), contentScale = ContentScale.Crop); return }
    Box(modifier.clip(RoundedCornerShape(radius)).background(Brush.linearGradient(listOf(Color.hsv(h, 0.55f, 0.95f), Color.hsv((h + 50) % 360, 0.75f, 0.65f)))), Alignment.Center) {
        Text("♪", color = Color.White.copy(alpha = 0.85f), fontSize = 22.sp)
    }
}

@Composable
fun Ico(kind: String, size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        fun tri(x0: Float, x1: Float) = Path().apply { moveTo(x0 * w, 0.2f * w); lineTo(x1 * w, 0.5f * w); lineTo(x0 * w, 0.8f * w); close() }
        when (kind) {
            "play" -> drawPath(tri(0.25f, 0.85f), color)
            "pause" -> {
                drawRect(color, Offset(0.22f * w, 0.18f * w), Size(0.18f * w, 0.64f * w))
                drawRect(color, Offset(0.60f * w, 0.18f * w), Size(0.18f * w, 0.64f * w))
            }
            else -> scale(if (kind == "prev") -1f else 1f, 1f, Offset(w / 2, w / 2)) {
                drawPath(tri(0.1f, 0.72f), color)
                drawRect(color, Offset(0.76f * w, 0.2f * w), Size(0.12f * w, 0.6f * w))
            }
        }
    }
}

fun fmt(ms: Long) = "%d:%02d".format(ms / 60000, ms / 1000 % 60)
