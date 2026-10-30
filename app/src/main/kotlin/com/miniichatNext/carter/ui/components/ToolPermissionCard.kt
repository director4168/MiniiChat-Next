package com.miniichatNext.carter.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.mcp.McpTool
import com.miniichatNext.carter.data.mcp.McpToolPermission

@Composable
fun ToolPermissionCard(
    tool: McpTool,
    perm: McpToolPermission?,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleApproval: (Boolean) -> Unit,
    defaultApproval: Boolean = true,
    showApproval: Boolean = true,
) {
    val enabled = perm?.enabled ?: true
    // 服务器默认值与逐工具覆盖使用执行侧相同的优先级。
    val requireApproval = perm?.requireApproval ?: defaultApproval

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                tool.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (tool.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    tool.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.mcp_tool_enable), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = onToggleEnabled)
            }
            if (showApproval) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.mcp_tool_approval), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Switch(checked = requireApproval, onCheckedChange = onToggleApproval, enabled = enabled)
                }
            }
        }
    }
}