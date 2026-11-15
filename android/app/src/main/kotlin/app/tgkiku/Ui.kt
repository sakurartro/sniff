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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.zIndex
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
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

val Accent = Color(0xFFFF5A7A)

@Composable
fun isDark(mode: Int) = mode != 1 // 1 light, anything else (incl. the old "system" 0) dark

@Composable
fun TgkikuTheme(theme: AppTheme, mode: Int, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colorSchemeFor(theme.name, isDark(mode)), content = content)
}

@Composable
fun Root(vm: Vm) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            when {
                !vm.ready -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }
                !vm.authorized -> LoginScreen(vm)
                else -> MainScreen(vm)
            }
            vm.error?.let {
                Text(
                    it, color = Color.White, fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.TopCenter).systemBarsPadding().padding(12.dp)
                        .clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary).clickable { vm.error = null }.padding(12.dp),
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
                TextButton({ vm.startQr() }) { Text("Войти по QR / ссылке", color = MaterialTheme.colorScheme.primary) }
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
                if (link == null) CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) else {
                    Image(remember(link) { qrBitmap(link) }, null, Modifier.size(220.dp).clip(RoundedCornerShape(12.dp)), filterQuality = FilterQuality.None)
                    Text("Отсканируйте в Telegram: Настройки → Устройства", textAlign = TextAlign.Center, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                    Primary("Открыть в Telegram на этом телефоне", true) { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
                }
                TextButton({ vm.backToPhone() }) { Text("Назад", color = MaterialTheme.colorScheme.primary) }
            }
        }
        if (vm.busy) LinearProgressIndicator(Modifier.padding(top = 12.dp).fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun Field(v: String, on: (String) -> Unit, hint: String, kb: KeyboardType, password: Boolean = false) {
    TextField(
        v, on, Modifier.fillMaxWidth().padding(bottom = 12.dp), singleLine = true, placeholder = { Text(hint) },
        shape = RoundedCornerShape(12.dp), keyboardOptions = KeyboardOptions(keyboardType = kb),
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface, cursorColor = MaterialTheme.colorScheme.primary),
    )
}

@Composable
fun Primary(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick, Modifier.fillMaxWidth().height(50.dp), enabled = enabled, shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)) {
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

fun qrBitmap(text: String): ImageBitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0) // size 0 = one pixel per module
    val px = IntArray(m.width * m.height) { if (m[it % m.width, it / m.width]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888).asImageBitmap()
}

// ---------- main ----------

private const val SEP = "\n"
private const val UNKNOWN = "Неизвестно"
private const val EMPTY = "Пусто. Выберите чаты в Настройки → Чаты и синхронизируйте."

@Composable
fun MainScreen(vm: Vm) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var full by rememberSaveable { mutableStateOf(false) }
    var stack by rememberSaveable { mutableStateOf("") } // routes opened inside the current tab, SEP-joined
    val route = stack.substringAfterLast(SEP)
    val push = { r: String -> stack = if (stack.isEmpty()) r else stack + SEP + r }
    val pop = { stack = if (SEP in stack) stack.substringBeforeLast(SEP) else "" }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Box(Modifier.weight(1f)) {
                when {
                    tab == 3 -> if (route == "manage") Sub(pop) { ChatsTab(vm) } else SettingsScreen(vm, vm.theme, { vm.setAppTheme(it) }, vm.mode, { vm.setThemeMode(it) }) { push("manage") }
                    route.isNotEmpty() -> Sub(pop) { Detail(vm, route, push) }
                    tab == 0 -> HomeTab(vm, push)
                    tab == 1 -> SearchTab(vm, push)
                    else -> LibraryTab(vm, push)
                }
            }
            if (vm.current != null) MiniPlayer(vm) { full = true }
            TabBar(tab) { tab = it; stack = "" }
        }
        AnimatedVisibility(full, enter = slideInVertically { it }, exit = slideOutVertically { it }) { NowPlaying(vm) { full = false } }
    }
    BackHandler(full) { full = false }
    BackHandler(!full && stack.isNotEmpty()) { pop() }
}

@Composable
fun Sub(back: () -> Unit, content: @Composable () -> Unit) {
    Column {
        TextButton(back) { Text("‹ Назад", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) }
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
fun TabBar(sel: Int, on: (Int) -> Unit) {
    Column(Modifier.background(MaterialTheme.colorScheme.background)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().height(56.dp)) {
            listOf("home" to "Главная", "search" to "Поиск", "library" to "Библиотека", "settings" to "Настройки").forEachIndexed { i, (k, s) ->
                val c = if (i == sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                Column(Modifier.weight(1f).fillMaxHeight().clickable { on(i) }, Arrangement.Center, Alignment.CenterHorizontally) {
                    Ico(k, 24.dp, c)
                    Text(s, fontSize = 11.sp, color = c)
                }
            }
        }
    }
}

@Composable
fun LargeTitle(s: String) = Text(s, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 8.dp))

@Composable
fun Section(s: String) = Text(s, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 8.dp))

@Composable
fun NavRow(text: String, detail: String? = null, accent: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground)
        detail?.let { Text(it, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp)) }
        Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 18.sp)
    }
    HorizontalDivider(Modifier.padding(start = 18.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
fun ChatTile(title: String, count: Int?, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.height(88.dp).clip(RoundedCornerShape(12.dp)).background(grad(title, dark = true)).clickable(onClick = onClick).padding(12.dp)) {
        Column(Modifier.align(Alignment.BottomStart)) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (count != null) Text("$count треков", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        }
    }
}

/** Two tiles per row. */
fun LazyListScope.chatTiles(chats: List<Chat>, tracks: List<TrackInfo>, push: (String) -> Unit) {
    items(chats.chunked(2), key = { it[0].id }) { row ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { c -> ChatTile(c.title, tracks.count { it.chatId == c.id }, Modifier.weight(1f)) { push("chat:${c.id}") } }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
fun HomeTab(vm: Vm, push: (String) -> Unit) {
    val byId = remember(vm.tracks) { vm.tracks.associateBy { it.documentId } }
    val recent = vm.recent.mapNotNull { byId[it] }
    val chats = vm.chats.filter { it.selected }
    LazyColumn(Modifier.fillMaxSize()) {
        item { LargeTitle("Главная") }
        if (recent.isNotEmpty()) {
            item { Section("Недавно играло") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(recent) { i, t ->
                        Column(Modifier.width(150.dp).clickable { vm.play(recent, i) }) {
                            Art(t, Modifier.size(150.dp), 12.dp)
                            Text(t.title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                            Text(vm.chats.firstOrNull { it.id == t.chatId }?.title ?: "", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item { Section("Из чатов") }
        if (chats.isEmpty()) item { Text(EMPTY, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(18.dp)) }
        chatTiles(chats, vm.tracks, push)
    }
}

@Composable
fun LibraryTab(vm: Vm, push: (String) -> Unit) {
    val added = remember(vm.tracks) { vm.tracks.sortedByDescending { it.messageId }.take(6) } // shortcut: messageId order, TrackInfo has no post date
    LazyColumn(Modifier.fillMaxSize()) {
        item { LargeTitle("Библиотека") }
        items(listOf("playlists" to "Плейлисты", "artists" to "Исполнители", "albums" to "Альбомы", "songs" to "Песни", "chats" to "Чаты")) { (r, n) ->
            NavRow(n, accent = true) { push(r) }
        }
        if (added.isNotEmpty()) {
            item { Section("Недавно добавлено") }
            items(added.chunked(2)) { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { t ->
                        Column(Modifier.weight(1f).clickable { vm.play(added, added.indexOf(t)) }) {
                            Art(t, Modifier.fillMaxWidth().aspectRatio(1f), 12.dp)
                            Text(t.title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                            Text(t.performer.ifBlank { UNKNOWN }, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun Detail(vm: Vm, route: String, push: (String) -> Unit) {
    val arg = route.substringAfter(':', "")
    when (route.substringBefore(':')) {
        "songs" -> TrackList(vm, "Песни", vm.tracks, EMPTY)
        "playlists" -> TrackList(vm, "Плейлисты", emptyList(), "Плейлисты появятся позже.")
        // shortcut: no album tag in TrackInfo, so everything is one "Неизвестно" group; upgrade when the core exposes tags
        "albums" -> GroupList("Альбомы", listOf(UNKNOWN to vm.tracks.size)) { push("songs") }
        "artists" -> GroupList("Исполнители", vm.tracks.flatMap { t -> t.artists.ifEmpty { listOf(UNKNOWN) } }.groupingBy { it }.eachCount().toList().sortedBy { it.first.lowercase() }) { push("artist:$it") }
        "artist" -> TrackList(vm, arg, vm.tracks.filter { t -> arg in t.artists.ifEmpty { listOf(UNKNOWN) } }, "")
        "chats" -> LazyColumn(Modifier.fillMaxSize()) { item { LargeTitle("Чаты") }; chatTiles(vm.chats.filter { it.selected }, vm.tracks, push) }
        "chat" -> {
            val id = arg.toLongOrNull()
            TrackList(vm, vm.chats.firstOrNull { it.id == id }?.title ?: "Чат", vm.tracks.filter { it.chatId == id }, EMPTY)
        }
    }
}

@Composable
fun GroupList(title: String, groups: List<Pair<String, Int>>, open: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { LargeTitle(title) }
        items(groups) { (n, c) -> NavRow(n, "$c") { open(n) } }
    }
}

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
        if (list.isEmpty()) item { Text(empty, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(18.dp)) }
        itemsIndexed(list, key = { _, t -> t.documentId }) { i, t -> TrackRow(t, vm.current?.documentId == t.documentId) { vm.play(list, i) } }
    }
}

@Composable
fun TrackRow(t: TrackInfo, active: Boolean, onClick: () -> Unit) {
    // not in the library (unliked): still findable by search, shown dimmed
    Row(Modifier.fillMaxWidth().alpha(if (t.liked) 1f else 0.5f).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Art(t, Modifier.size(48.dp), 12.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(t.title, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground)
            Text(t.performer.ifBlank { "Неизвестный исполнитель" }, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(fmt(t.duration.toLong() * 1000), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SearchTab(vm: Vm, push: (String) -> Unit) {
    val q = vm.query
    val chats = vm.chats.filter { it.selected }
    val found = chats.filter { it.title.contains(q, true) }
    val artists = vm.results.flatMap { it.artists }.distinct().take(10)
    val tracks = vm.results.take(30)
    LazyColumn(Modifier.fillMaxSize()) {
        item { LargeTitle("Поиск") }
        item {
            TextField(
                q, { vm.query = it; vm.search(it) }, Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), singleLine = true,
                placeholder = { Text("Треки, исполнители, чаты") }, shape = RoundedCornerShape(12.dp),
                colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface, cursorColor = MaterialTheme.colorScheme.primary),
            )
        }
        if (q.isBlank()) chatTiles(chats, vm.tracks, push)
        else {
            if (tracks.isNotEmpty()) {
                item { Section("Треки") }
                itemsIndexed(tracks) { i, t -> TrackRow(t, vm.current?.documentId == t.documentId) { vm.play(tracks, i) } }
            }
            if (artists.isNotEmpty()) {
                item { Section("Исполнители") }
                items(artists) { a -> NavRow(a) { push("artist:$a") } }
            }
            if (found.isNotEmpty()) {
                item { Section("Чаты") }
                items(found) { c -> NavRow(c.title) { push("chat:${c.id}") } }
            }
            if (tracks.isEmpty() && found.isEmpty()) item { Text("Ничего не найдено.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(18.dp)) }
        }
    }
}
@Composable
fun ChatsTab(vm: Vm) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { LargeTitle("Чаты") }
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ vm.refreshChats() }, enabled = !vm.busy, shape = RoundedCornerShape(12.dp)) { Text("Обновить список", color = MaterialTheme.colorScheme.primary) }
                    Button({ vm.sync() }, enabled = !vm.busy, shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)) { Text("Синхронизировать") }
                }
                vm.syncText?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)) }
                if (vm.busy) LinearProgressIndicator(Modifier.padding(top = 8.dp).fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
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
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).pointerInput(Unit) { detectTapGestures() }.systemBarsPadding().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.padding(bottom = 24.dp).size(width = 40.dp, height = 5.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.outlineVariant).clickable(onClick = close))
        Art(t, Modifier.fillMaxWidth().aspectRatio(1f), 14.dp)
        Spacer(Modifier.height(28.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Ico("x", 30.dp, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.clickable { vm.dismiss() })
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(t.performer.ifBlank { "Неизвестный исполнитель" }, fontSize = 18.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            }
            Ico(if (t.liked) "heart" else "heart0", 30.dp, if (t.liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, Modifier.clickable { vm.toggleLike() })
        }
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
            val on = MaterialTheme.colorScheme.primary; val off = MaterialTheme.colorScheme.onSurfaceVariant
            Ico("shuffle", 28.dp, if (vm.shuffle) on else off, Modifier.clickable { vm.toggleShuffle() })
            Ico("prev", 44.dp, MaterialTheme.colorScheme.onBackground, Modifier.clickable { vm.prev() })
            Ico(if (vm.playing) "pause" else "play", 64.dp, MaterialTheme.colorScheme.onBackground, Modifier.clickable { vm.toggle() })
            Ico("next", 44.dp, MaterialTheme.colorScheme.onBackground, Modifier.clickable { vm.next() })
            Ico(if (vm.repeat == 2) "repeat1" else "repeat", 28.dp, if (vm.repeat > 0) on else off, Modifier.clickable { vm.cycleRepeat() })
            Ico("spark", 28.dp, if (vm.recsOn) on else off, Modifier.clickable { vm.toggleRecs() })
        }
        var showQueue by remember { mutableStateOf(false) }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            vm.undo?.let { u ->
                Text("«${u.title}» убрано из медиатеки", Modifier.weight(1f, fill = false), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton({ vm.restore() }) { Text("Вернуть") }
            }
            Spacer(Modifier.weight(1f))
            Ico("queue", 28.dp, MaterialTheme.colorScheme.onBackground, Modifier.clickable { showQueue = true })
        }
        if (showQueue) QueueSheet(vm) { showQueue = false }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(vm: Vm, dismiss: () -> Unit) {
    ModalBottomSheet(dismiss) {
        val cur = vm.current
        val rowH = 56.dp
        val rowHPx = with(androidx.compose.ui.platform.LocalDensity.current) { rowH.toPx() }
        // unique keys even when the same track is queued twice
        val keys = remember(vm.queue) { val seen = HashMap<Long, Int>(); vm.queue.map { t -> "${t.documentId}#${seen.merge(t.documentId, 1, Int::plus)}" } }
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            if (cur != null) {
                item { Text("Сейчас играет", fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp)) }
                item { QueueRow(cur, Modifier.height(rowH), cur.rec && !cur.liked) }
            }
            item { Text("Далее", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)) }
            itemsIndexed(vm.queue, key = { i, _ -> keys[i] }) { i, t ->
                if (i <= vm.index) return@itemsIndexed
                val idx by rememberUpdatedState(i)
                var dy by remember { mutableFloatStateOf(0f) }
                val box = rememberSwipeToDismissBoxState(confirmValueChange = { if (it != SwipeToDismissBoxValue.Settled) vm.removeAt(idx); true })
                SwipeToDismissBox(box, modifier = Modifier.height(rowH).offset { androidx.compose.ui.unit.IntOffset(0, dy.toInt()) }.zIndex(if (dy != 0f) 1f else 0f),
                    backgroundContent = { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer)) }) {
                    QueueRow(t, Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow).clickable { vm.jumpTo(idx) }, t.rec && !t.liked) {
                        Text("≡", fontSize = 24.sp, modifier = Modifier.padding(horizontal = 12.dp).pointerInput(Unit) {
                            detectDragGesturesAfterLongPress({}, { dy = 0f }, { dy = 0f }) { ch, d ->
                                ch.consume(); dy += d.y
                                // swap with the neighbour once dragged past half a row
                                val to = if (dy > rowHPx / 2) idx + 1 else if (dy < -rowHPx / 2) idx - 1 else idx
                                if (to != idx && to > vm.index && to < vm.queue.size) { vm.moveTrack(idx, to); dy -= (to - idx) * rowHPx }
                            }
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueRow(t: TrackInfo, modifier: Modifier, rec: Boolean = false, trailing: @Composable () -> Unit = {}) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Art(t, Modifier.size(44.dp), 6.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(if (rec) "✨ ${t.title}" else t.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t.performer.ifBlank { "Неизвестный исполнитель" }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

// ---------- bits ----------

/** Gradient from a pair of hues that depends on the name; dark = readable under white text. */
fun grad(name: String, dark: Boolean = false): Brush {
    val h = (name.hashCode().toUInt() % 360u).toFloat()
    return Brush.linearGradient(if (dark) listOf(Color.hsv(h, 0.6f, 0.7f), Color.hsv((h + 50) % 360, 0.75f, 0.45f)) else listOf(Color.hsv(h, 0.55f, 0.95f), Color.hsv((h + 50) % 360, 0.75f, 0.65f)))
}

/** Cover when one is found, else a gradient derived from the title. */
@Composable
fun Art(t: TrackInfo, modifier: Modifier, radius: Dp) {
    val cover by produceState<Bitmap?>(null, t.documentId) { value = Covers.bitmap(t) }
    cover?.let { Image(it.asImageBitmap(), null, modifier.clip(RoundedCornerShape(radius)), contentScale = ContentScale.Crop); return }
    Box(modifier.clip(RoundedCornerShape(radius)).background(grad(t.title + t.performer)), Alignment.Center) {
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
            "shuffle" -> {
                val st = Stroke(0.08f * w, cap = StrokeCap.Round)
                drawPath(Path().apply { moveTo(0.12f * w, 0.28f * w); lineTo(0.35f * w, 0.28f * w); lineTo(0.65f * w, 0.72f * w); lineTo(0.8f * w, 0.72f * w) }, color, style = st)
                drawPath(Path().apply { moveTo(0.12f * w, 0.72f * w); lineTo(0.35f * w, 0.72f * w); lineTo(0.65f * w, 0.28f * w); lineTo(0.8f * w, 0.28f * w) }, color, style = st)
                drawPath(Path().apply { moveTo(0.8f * w, 0.16f * w); lineTo(0.92f * w, 0.28f * w); lineTo(0.8f * w, 0.4f * w); close() }, color)
                drawPath(Path().apply { moveTo(0.8f * w, 0.6f * w); lineTo(0.92f * w, 0.72f * w); lineTo(0.8f * w, 0.84f * w); close() }, color)
            }
            "repeat", "repeat1" -> {
                val st = Stroke(0.08f * w, cap = StrokeCap.Round, join = StrokeJoin.Round)
                drawPath(Path().apply { moveTo(0.78f * w, 0.36f * w); lineTo(0.78f * w, 0.28f * w); lineTo(0.14f * w, 0.28f * w); lineTo(0.14f * w, 0.5f * w) }, color, style = st)
                drawPath(Path().apply { moveTo(0.22f * w, 0.64f * w); lineTo(0.22f * w, 0.72f * w); lineTo(0.86f * w, 0.72f * w); lineTo(0.86f * w, 0.5f * w) }, color, style = st)
                drawPath(Path().apply { moveTo(0.66f * w, 0.14f * w); lineTo(0.82f * w, 0.28f * w); lineTo(0.66f * w, 0.42f * w); close() }, color)
                drawPath(Path().apply { moveTo(0.34f * w, 0.58f * w); lineTo(0.18f * w, 0.72f * w); lineTo(0.34f * w, 0.86f * w); close() }, color)
                if (kind == "repeat1") drawPath(Path().apply { moveTo(0.42f * w, 0.44f * w); lineTo(0.5f * w, 0.38f * w); lineTo(0.5f * w, 0.62f * w) }, color, style = Stroke(0.06f * w, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            "heart", "heart0" -> {
                val p = Path().apply {
                    moveTo(0.5f * w, 0.85f * w)
                    cubicTo(0.05f * w, 0.55f * w, 0.1f * w, 0.12f * w, 0.5f * w, 0.3f * w)
                    cubicTo(0.9f * w, 0.12f * w, 0.95f * w, 0.55f * w, 0.5f * w, 0.85f * w)
                    close()
                }
                if (kind == "heart") drawPath(p, color) else drawPath(p, color, style = Stroke(0.07f * w, join = StrokeJoin.Round))
            }
            "x" -> {
                drawLine(color, Offset(0.25f * w, 0.25f * w), Offset(0.75f * w, 0.75f * w), 0.07f * w, StrokeCap.Round)
                drawLine(color, Offset(0.75f * w, 0.25f * w), Offset(0.25f * w, 0.75f * w), 0.07f * w, StrokeCap.Round)
            }
            "spark" -> drawPath(Path().apply {
                // four-pointed star: recommendations
                moveTo(0.5f * w, 0.1f * w); quadraticTo(0.55f * w, 0.45f * w, 0.9f * w, 0.5f * w); quadraticTo(0.55f * w, 0.55f * w, 0.5f * w, 0.9f * w)
                quadraticTo(0.45f * w, 0.55f * w, 0.1f * w, 0.5f * w); quadraticTo(0.45f * w, 0.45f * w, 0.5f * w, 0.1f * w); close()
            }, color)
            "queue" -> for (k in 0..2) {
                val y = (0.25f + 0.25f * k) * w
                drawRect(color, Offset(0.1f * w, y - 0.04f * w), Size(0.12f * w, 0.08f * w))
                drawRect(color, Offset(0.3f * w, y - 0.04f * w), Size(0.6f * w, 0.08f * w))
            }
            "home" -> {
                val st = Stroke(0.08f * w, cap = StrokeCap.Round, join = StrokeJoin.Round)
                drawPath(Path().apply { moveTo(0.12f * w, 0.48f * w); lineTo(0.5f * w, 0.16f * w); lineTo(0.88f * w, 0.48f * w) }, color, style = st)
                drawPath(Path().apply { moveTo(0.22f * w, 0.42f * w); lineTo(0.22f * w, 0.84f * w); lineTo(0.78f * w, 0.84f * w); lineTo(0.78f * w, 0.42f * w) }, color, style = st)
            }
            "search" -> {
                drawCircle(color, 0.26f * w, Offset(0.43f * w, 0.43f * w), style = Stroke(0.08f * w))
                drawLine(color, Offset(0.63f * w, 0.63f * w), Offset(0.88f * w, 0.88f * w), 0.08f * w, StrokeCap.Round)
            }
            "library" -> for (k in 0..2) drawRect(color, Offset((0.14f + 0.26f * k) * w, 0.16f * w), Size(0.16f * w, 0.68f * w))
            "settings" -> {
                drawCircle(color, 0.2f * w, style = Stroke(0.08f * w))
                for (k in 0 until 8) rotate(k * 45f) { drawLine(color, Offset(0.5f * w, 0.12f * w), Offset(0.5f * w, 0.26f * w), 0.08f * w) }
            }
            else -> scale(if (kind == "prev") -1f else 1f, 1f, Offset(w / 2, w / 2)) {
                drawPath(tri(0.1f, 0.72f), color)
                drawRect(color, Offset(0.76f * w, 0.2f * w), Size(0.12f * w, 0.6f * w))
            }
        }
    }
}

fun fmt(ms: Long) = "%d:%02d".format(ms / 60000, ms / 1000 % 60)
