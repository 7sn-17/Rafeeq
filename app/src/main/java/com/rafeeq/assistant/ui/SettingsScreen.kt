package com.rafeeq.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rafeeq.assistant.tts.TtsVoice
import com.rafeeq.assistant.ui.theme.rafeeqHeaderGradient

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    ttsEnabled: Boolean,
    ttsMuted: Boolean,
    voices: List<TtsVoice>,
    selectedVoice: String?,
    appVersion: String,
    onThemeModeChange: (ThemeMode) -> Unit,
    onTtsEnabledChange: (Boolean) -> Unit,
    onToggleMute: () -> Unit,
    onVoiceChange: (String?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(rafeeqHeaderGradient())
        ) {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "رجوع",
                            tint = Color.White
                        )
                    }
                },
                title = {
                    Text(text = "الإعدادات", color = Color.White, fontWeight = FontWeight.Bold)
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            SectionHeader(text = "الصوت")

            SettingRow(
                title = "الرد الصوتي",
                subtitle = "يسمح لرفيق بالإجابة بصوت عالٍ",
                trailing = {
                    Switch(checked = ttsEnabled, onCheckedChange = onTtsEnabledChange)
                }
            )

            if (ttsEnabled) {
                SettingRow(
                    title = "تشغيل الصوت",
                    subtitle = if (ttsMuted) "مكتوم حالياً" else "يعمل حالياً",
                    trailing = {
                        Switch(checked = !ttsMuted, onCheckedChange = { onToggleMute() })
                    }
                )

                VoicePicker(
                    voices = voices,
                    selectedVoice = selectedVoice,
                    onVoiceChange = onVoiceChange
                )
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

            SectionHeader(text = "المظهر")

            ThemeModeSelector(themeMode = themeMode, onThemeModeChange = onThemeModeChange)

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

            SectionHeader(text = "حول التطبيق")

            Text(
                text = "الإصدار $appVersion",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(
                text = subtitle,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        trailing()
    }
}

@Composable
private fun VoicePicker(
    voices: List<TtsVoice>,
    selectedVoice: String?,
    onVoiceChange: (String?) -> Unit
) {
    Text(
        text = "الصوت المفضل",
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp)
    )

    if (voices.isEmpty()) {
        Text(
            text = "لا توجد أصوات عربية مثبتة على هذا الجهاز",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        return
    }

    RadioRow(
        selected = selectedVoice == null,
        label = "الصوت الافتراضي"
    ) { onVoiceChange(null) }

    voices.forEach { voice ->
        RadioRow(
            selected = selectedVoice == voice.name,
            label = voice.displayName
        ) { onVoiceChange(voice.name) }
    }
}

@Composable
private fun RadioRow(
    selected: Boolean,
    label: String,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 2.dp)
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label, fontSize = 15.sp)
    }
}

@Composable
private fun ThemeModeSelector(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit
) {
    ThemeMode.entries.forEach { mode ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .selectable(
                    selected = themeMode == mode,
                    onClick = { onThemeModeChange(mode) }
                )
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            RadioButton(selected = themeMode == mode, onClick = { onThemeModeChange(mode) })
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = mode.label(), fontSize = 15.sp)
        }
    }
}

private fun ThemeMode.label(): String = when (this) {
    ThemeMode.LIGHT -> "فاتح"
    ThemeMode.DARK -> "داكن"
    ThemeMode.SYSTEM -> "حسب النظام"
}
