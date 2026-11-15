package app.tgkiku

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(vm: Vm, selected: AppTheme, onSelect: (AppTheme) -> Unit, mode: Int, onMode: (Int) -> Unit, openChats: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            LargeTitle("Настройки")
            SettingsSection("Telegram") {
                Row(Modifier.fillMaxWidth().clickable(onClick = openChats).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Чаты", fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 18.sp)
                }
            }
            RecsSection(vm)
            SettingsSection("Оформление") {
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1 to "Светлый", 2 to "Тёмный").forEach { (m, s) ->
                        val on = (m == 1) == (mode == 1)
                        Text(
                            s, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                            color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.background)
                                .clickable { onMode(m) }.padding(vertical = 10.dp),
                        )
                    }
                }
                AppTheme.entries.forEachIndexed { i, t ->
                    if (i > 0) HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth().clickable { onSelect(t) }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(t.title, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        if (t == selected) Text("✓", color = MaterialTheme.colorScheme.primary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/** Bots the app was tested with. */
val KNOWN_BOTS = listOf("deezload2bot")

@Composable
private fun RecsSection(vm: Vm) {
    SettingsSection("Музыкальные боты") {
        Row(Modifier.fillMaxWidth().clickable { vm.toggleRecs() }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Рекомендации в очереди", fontSize = 16.sp, modifier = Modifier.weight(1f))
            Switch(vm.recsOn, { vm.toggleRecs() })
        }
        HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            "Бот-источник даёт музыку для поиска и рекомендаций: каждый 4-й трек в очереди — новый, похожий на то, что вы слушаете. " +
                "Ниже — боты, с которыми приложение проверено. Это не реклама, мы с ними никак не связаны.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        KNOWN_BOTS.forEach { b ->
            HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("@$b", fontSize = 16.sp, modifier = Modifier.weight(1f))
                if (b in vm.botSources) Text("✓ Подключён", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(12.dp))
                else TextButton({ vm.connectBot(b) }, enabled = !vm.busy) { Text("Подключить") }
            }
        }
        HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        var own by remember { mutableStateOf("") }
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                own, { own = it }, Modifier.weight(1f), placeholder = { Text("Свой бот: @username") }, singleLine = true,
                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
            )
            TextButton({ vm.connectBot(own); own = "" }, enabled = own.isNotBlank() && !vm.busy) { Text("Подключить") }
        }
    }
}

@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title.uppercase(), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 36.dp, top = 16.dp, bottom = 6.dp))
    Column(Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface), content = content)
}
