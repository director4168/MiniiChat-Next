/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.store.AppSettings
import com.miniichatNext.carter.ui.components.SectionCard
import com.miniichatNext.carter.ui.components.SectionHeader
import com.miniichatNext.carter.ui.components.SettingsTopBar

@Composable
fun MorePreferencesScreen(
    settings: AppSettings,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.statusBars.asPaddingValues())
    ) {
        SettingsTopBar(stringResource(R.string.more_preferences_title), onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            SectionHeader(stringResource(R.string.pref_conversations))
            SectionCard {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    PreferenceSwitch(stringResource(R.string.pref_auto_new_chat), stringResource(R.string.pref_auto_new_chat_desc),
                        settings.autoNewChatOnLaunch) { v -> onChange { it.copy(autoNewChatOnLaunch = v) } }
                }
            }
            SectionHeader(stringResource(R.string.pref_display))
            SectionCard {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.pref_font_size, settings.fontSizeSp.toInt()))
                    Slider(value = settings.fontSizeSp, onValueChange = { v -> onChange { it.copy(fontSizeSp = v) } },
                        valueRange = 12f..22f, steps = 9)
                    Text(stringResource(R.string.pref_font_preview), fontSize = settings.fontSizeSp.sp)
                    PreferenceSwitch(stringResource(R.string.pref_reasoning), stringResource(R.string.pref_reasoning_desc),
                        settings.showReasoningCard) { v -> onChange { it.copy(showReasoningCard = v) } }
                    PreferenceSwitch(stringResource(R.string.pref_usage), stringResource(R.string.pref_usage_desc),
                        settings.showCardTokenUsage) { v -> onChange { it.copy(showCardTokenUsage = v) } }
                }
            }
            SectionHeader(stringResource(R.string.pref_interaction))
            SectionCard {
                Column(Modifier.padding(16.dp)) {
                    PreferenceSwitch(stringResource(R.string.pref_enter), stringResource(R.string.pref_enter_desc),
                        settings.sendOnEnter) { v -> onChange { it.copy(sendOnEnter = v) } }
                    PreferenceSwitch(stringResource(R.string.pref_paste), stringResource(R.string.pref_paste_desc),
                        settings.pasteAsAttachment) { v -> onChange { it.copy(pasteAsAttachment = v) } }
                }
            }
            SectionHeader(stringResource(R.string.section_preferences))
            SectionCard {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    PreferenceSwitch(
                        title = stringResource(R.string.preference_code_wrap),
                        subtitle = stringResource(R.string.preference_code_wrap_desc),
                        checked = settings.codeWrap,
                        onCheckedChange = { value -> onChange { it.copy(codeWrap = value) } }
                    )
                    PreferenceSwitch(
                        title = stringResource(R.string.preference_code_fold),
                        subtitle = stringResource(R.string.preference_code_fold_desc),
                        checked = settings.codeFold,
                        onCheckedChange = { value -> onChange { it.copy(codeFold = value) } }
                    )
                    PreferenceSwitch(
                        title = stringResource(R.string.preference_latex),
                        subtitle = stringResource(R.string.preference_latex_desc),
                        checked = settings.latexEnabled,
                        onCheckedChange = { value -> onChange { it.copy(latexEnabled = value) } }
                    )
                }
            }
        }
    }
}

@Composable
private fun PreferenceSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
