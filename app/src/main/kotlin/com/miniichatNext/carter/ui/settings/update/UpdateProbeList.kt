/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.ui.settings.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.update.UpdateNodeProbe
import com.miniichatNext.carter.data.update.UpdateProbeStatus
import java.util.Locale

@Composable
internal fun UpdateProbeList(probes: List<UpdateNodeProbe>, selected: String) {
    Text(stringResource(R.string.update_probe_list_title), style = MaterialTheme.typography.titleSmall)
    // 节点列表独立滚动，固定测量高度，避免撑出下载弹窗的操作区。
    LazyColumn(Modifier.fillMaxWidth().height(240.dp)) {
        items(probes, key = { it.key }) { probe ->
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(probe.key, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (probe.key == selected && probe.status == UpdateProbeStatus.SUCCESS) {
                        Text(stringResource(R.string.update_probe_selected), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    when (probe.status) {
                        UpdateProbeStatus.WAITING -> Text(stringResource(R.string.update_probe_waiting), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        UpdateProbeStatus.TESTING -> {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.update_probe_testing), style = MaterialTheme.typography.labelSmall)
                        }
                        UpdateProbeStatus.SUCCESS -> Text(
                            stringResource(R.string.update_probe_latency, probe.latencyMs ?: 0L),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        UpdateProbeStatus.FAILED -> Text(stringResource(R.string.update_probe_failed), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
                if (probe.status == UpdateProbeStatus.SUCCESS) {
                    Text(stringResource(R.string.update_probe_speed,
                        String.format(Locale.ROOT, "%.1f KB/s", probe.speedBytesPerSec / 1024.0)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (probe.status == UpdateProbeStatus.FAILED) {
                    Text(probe.error.orEmpty(), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
        }
    }
}
