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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(selected: AppTheme, onSelect: (AppTheme) -> Unit, openChats: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            LargeTitle("Настройки")
            SettingsSection("Telegram") {
                Row(Modifier.fillMaxWidth().clickable(onClick = openChats).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Чаты", fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 18.sp)
                }
            }
            SettingsSection("Оформление") {
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

@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title.uppercase(), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 36.dp, top = 16.dp, bottom = 6.dp))
    Column(Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface), content = content)
}
