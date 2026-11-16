package app.sniff

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.sniff_ffi.PlaylistInfo
import uniffi.sniff_ffi.TrackInfo

/** Custom image if set, else a 2x2 collage of the first tracks (one cover for fewer than 4). */
@Composable
fun PlaylistCover(vm: Vm, p: PlaylistInfo, modifier: Modifier, radius: Dp) {
    val m = modifier.clip(RoundedCornerShape(radius))
    if (p.cover != null) {
        val bmp by produceState<Bitmap?>(null, p.cover) { value = withContext(Dispatchers.IO) { Covers.decode(java.io.File(p.cover)) } }
        bmp?.let { Image(it.asImageBitmap(), null, m, contentScale = ContentScale.Crop); return }
    }
    val first by produceState(emptyList<TrackInfo>(), p.id, vm.plVersion) { value = vm.playlistTracks(p.id).take(4) }
    when {
        first.size >= 4 -> Column(m) {
            for (r in 0..1) Row(Modifier.weight(1f)) { for (c in 0..1) Art(first[r * 2 + c], Modifier.weight(1f).fillMaxHeight(), 0.dp) }
        }
        first.isNotEmpty() -> Art(first[0], m, 0.dp)
        else -> Box(m.background(grad(p.name)), Alignment.Center) {
            if (p.id == LIKED) Ico("heart", 40.dp, Color.White) else Text("♪", color = Color.White.copy(alpha = 0.85f), fontSize = 28.sp)
        }
    }
}

@Composable
fun PlaylistTile(vm: Vm, p: PlaylistInfo, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick)) {
        PlaylistCover(vm, p, Modifier.fillMaxWidth().aspectRatio(1f), 12.dp)
        Text(p.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text(tracksCount(p.count), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun PlaylistsScreen(vm: Vm, push: (String) -> Unit) {
    var naming by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize()) {
        item { LargeTitle("Плейлисты") }
        item { NavRow("Новый плейлист", accent = true) { naming = true } }
        items(vm.playlists.chunked(2), key = { it[0].id }) { row ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { p -> PlaylistTile(vm, p, Modifier.weight(1f)) { push("playlist:${p.id}") } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
    if (naming) NameDialog("Новый плейлист", "", { naming = false }) { vm.createPlaylist(it) { id -> push("playlist:$id") } }
}

@Composable
fun PlaylistScreen(vm: Vm, id: Long, back: () -> Unit) {
    val p = vm.playlists.firstOrNull { it.id == id } ?: return
    val list by produceState(emptyList<TrackInfo>(), id, vm.plVersion) { value = vm.playlistTracks(id) }
    var edit by remember { mutableStateOf<List<TrackInfo>?>(null) } // working copy while «Изменить» is on
    var naming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { vm.setPlaylistCover(id, it) } }
    val rowH = 60.dp
    val rowHPx = with(LocalDensity.current) { rowH.toPx() }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.align(Alignment.End)) {
                    TextButton({
                        val e = edit
                        if (e == null) {
                            // not loaded yet (or failed): saving an empty copy would wipe the playlist
                            if (p.count == 0 || list.isNotEmpty()) edit = list
                        } else {
                            if (e.map { it.documentId } != list.map { it.documentId }) vm.savePlaylistOrder(id, e.map { it.documentId })
                            edit = null
                        }
                    }) {
                        Text(if (edit == null) "Изменить" else "Готово", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    }
                }
                PlaylistCover(vm, p, Modifier.size(220.dp), 14.dp)
                Text(p.name, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
                Text(tracksCount(p.count), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(14.dp))
                if (edit == null) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Pill("play", "Слушать", Modifier.weight(1f), list.isNotEmpty()) { vm.play(list, 0) }
                    Pill("shuffle", "Перемешать", Modifier.weight(1f), list.isNotEmpty()) { vm.playShuffled(list) }
                } else Column(Modifier.fillMaxWidth()) {
                    if (id != LIKED) NavRow("Переименовать", accent = true) { naming = true }
                    NavRow("Выбрать обложку", accent = true) { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    if (p.cover != null) NavRow("Обложка-коллаж", accent = true) { vm.setPlaylistCover(id, null) }
                    if (id != LIKED) NavRow("Удалить плейлист", accent = true) { deleting = true }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        val e = edit
        if (e == null) {
            if (list.isEmpty()) item { Text(if (id == LIKED) "Пусто. Ставьте ❤ в плеере." else "Пусто. Добавляйте треки через ⋯ в плеере.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(18.dp)) }
            itemsIndexed(list, key = { _, t -> t.documentId }) { i, t -> TrackRow(t, vm.current?.documentId == t.documentId) { vm.play(list, i) } }
        } else itemsIndexed(e, key = { _, t -> t.documentId }) { i, t ->
            val idx by rememberUpdatedState(i)
            var dy by remember { mutableFloatStateOf(0f) }
            Row(Modifier.height(rowH).fillMaxWidth().offset { IntOffset(0, dy.toInt()) }.zIndex(if (dy != 0f) 1f else 0f).background(MaterialTheme.colorScheme.background).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(24.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.error).clickable { edit = edit!!.filter { it.documentId != t.documentId } }, Alignment.Center) {
                    Box(Modifier.size(width = 12.dp, height = 2.dp).background(Color.White))
                }
                Art(t, Modifier.padding(start = 12.dp).size(44.dp), 8.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(t.performer, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DragHandle({ idx }, rowHPx, { dy }, { dy = it }) { f, to ->
                    val cur = edit!!
                    (to in cur.indices).also { if (it) edit = cur.toMutableList().apply { add(to, removeAt(f)) } }
                }
            }
        }
    }
    if (naming) NameDialog("Переименовать", p.name, { naming = false }) { vm.renamePlaylist(id, it) }
    if (deleting) AlertDialog(
        { deleting = false },
        confirmButton = { TextButton({ deleting = false; vm.deletePlaylist(id); back() }) { Text("Удалить", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ deleting = false }) { Text("Отмена") } },
        title = { Text("Удалить «${p.name}»?") },
        text = { Text("Треки останутся в медиатеке и в Telegram.") },
    )
}

@Composable
private fun Pill(icon: String, text: String, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier.height(46.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface).clickable(enabled, onClick = onClick),
        Arrangement.Center, Alignment.CenterVertically,
    ) {
        Ico(icon, 20.dp, MaterialTheme.colorScheme.primary)
        Text(text, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
fun NameDialog(title: String, initial: String, dismiss: () -> Unit, ok: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        dismiss,
        confirmButton = { TextButton({ ok(name.trim()); dismiss() }, enabled = name.isNotBlank()) { Text("Готово") } },
        dismissButton = { TextButton(dismiss) { Text("Отмена") } },
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("Название") }) },
    )
}

/** ⋯ of the player: add to playlist, go to the artist, open the message in Telegram. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackMenu(vm: Vm, t: TrackInfo, dismiss: () -> Unit, goto: (String) -> Unit) {
    val ctx = LocalContext.current
    var adding by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    ModalBottomSheet(dismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(t.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
            if (!adding) {
                NavRow("Добавить в плейлист") { adding = true }
                t.artists.forEach { a -> NavRow("К исполнителю: $a") { dismiss(); goto("artist:$a") } }
                NavRow("Показать в чате") { dismiss(); openInTelegram(ctx, t)?.let { vm.error = it } }
            } else {
                val inPl by produceState(emptyList<Long>(), t.documentId, vm.plVersion) { value = vm.playlistsWith(t) }
                NavRow("Новый плейлист…", accent = true) { naming = true }
                LazyColumn {
                    items(vm.playlists, key = { it.id }) { p ->
                        val on = p.id in inPl
                        Row(Modifier.fillMaxWidth().clickable { vm.setIn(p.id, t, !on) }.padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            PlaylistCover(vm, p, Modifier.size(44.dp), 8.dp)
                            Text(p.name, Modifier.weight(1f).padding(horizontal = 12.dp), fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (on) Text("✓", color = MaterialTheme.colorScheme.primary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
    if (naming) NameDialog("Новый плейлист", "", { naming = false }) { vm.createPlaylist(it, t) }
}

/** Opens the track's message in the Telegram app; returns an error text if that is impossible. */
fun openInTelegram(ctx: Context, t: TrackInfo): String? {
    val id = t.chatId
    // Bot API ids: users/bots > 0, channels and supergroups -100xxxxxxxxxx, basic groups -xxxx
    // shortcut: tg://openmessage is honoured by the official app; other clients may only open the chat
    val url = when {
        id > 0 -> "tg://openmessage?user_id=$id&message_id=${t.messageId}"
        id < -1_000_000_000_000 -> "https://t.me/c/${-id - 1_000_000_000_000}/${t.messageId}"
        else -> "tg://openmessage?chat_id=${-id}&message_id=${t.messageId}"
    }
    return try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))); null } catch (_: ActivityNotFoundException) { "Telegram не установлен" }
}
