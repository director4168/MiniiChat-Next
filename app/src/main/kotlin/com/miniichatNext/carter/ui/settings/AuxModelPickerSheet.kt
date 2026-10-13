package com.miniichatNext.carter.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.model.ProviderConfig
import com.miniichatNext.carter.ui.chat.ModelPickerContent

/**
 * 辅助模型（标题生成/上下文压缩/候选回复/OCR）的选择器。
 * 与chat页模型选择列表共享ModelPickerContent：搜索+按服务商分组+选中行滚到顶部。
 * 顶部新增「未设置」按钮，便于用户清空。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuxModelPickerSheet(
    label: String,
    providers: List<ProviderConfig>,
    selected: String, // "providerId::model"形式；空串=未设置
    onPick: (ref: String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 解析当前ref->(providerId, model)
    val parts = selected.split("::", limit = 2)
    val activeProviderId = parts.getOrNull(0).orEmpty()
    val activeModel = parts.getOrNull(1).orEmpty()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .heightIn(max = 640.dp)
        ) {
            // 标题+未设置按钮
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp)
            ) {
                Text(
                    label,
                    style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.align(Alignment.CenterStart)
                )
                TextButton(
                    onClick = onClear,
                    modifier = Modifier.align(Alignment.CenterEnd)
                ) {
                    Text(stringResource(R.string.aux_model_not_set))
                }
            }
            Spacer(Modifier.height(10.dp))

            if (selected.isBlank()) {
                Text(
                    stringResource(R.string.aux_model_not_set_hint),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
                Spacer(Modifier.height(8.dp))
            }

            ModelPickerContent(
                providers = providers,
                activeProviderId = activeProviderId,
                activeModel = activeModel,
                onPick = { pid, m -> onPick("$pid::$m"); onDismiss() },
                heightMax = 460.dp,
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}