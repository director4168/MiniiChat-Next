/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.mcp.McpTool
import com.miniichatNext.carter.data.mcp.McpToolPermission
import com.miniichatNext.carter.ui.theme.toolRiskContainer
import kotlinx.coroutines.delay

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
    val requireApproval = perm?.takeUnless { it.inheritApproval }?.requireApproval ?: defaultApproval
    var riskToolToDisable by remember { mutableStateOf<String?>(null) }
    var rootCooldown by remember { mutableIntStateOf(0) }
    LaunchedEffect(riskToolToDisable) {
        if (riskToolToDisable == "execute_shell_root") {
            rootCooldown = 3
            while (rootCooldown > 0) {
                delay(1_000)
                rootCooldown--
            }
        } else {
            rootCooldown = 0
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = toolRiskContainer(tool.name) ?: MaterialTheme.colorScheme.surfaceVariant
        )
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
                    Switch(
                        checked = requireApproval,
                        onCheckedChange = { checked ->
                            if (!checked && requireApproval &&
                                (tool.name == "execute_shell_root" || tool.name == "execute_shell_shizuku")) {
                                riskToolToDisable = tool.name
                            } else {
                                onToggleApproval(checked)
                            }
                        },
                        enabled = enabled,
                    )
                }
            }
        }
    }
    if (riskToolToDisable != null) {
        val isRoot = riskToolToDisable == "execute_shell_root"
        AlertDialog(
            onDismissRequest = { riskToolToDisable = null },
            title = {
                Text(stringResource(if (isRoot) R.string.root_approval_disable_title else R.string.shizuku_approval_disable_title))
            },
            text = {
                Column {
                    Text(stringResource(if (isRoot) R.string.root_approval_disable_message else R.string.shizuku_approval_disable_message))
                    if (isRoot && rootCooldown > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.root_approval_disable_wait, rootCooldown),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !isRoot || rootCooldown == 0,
                    onClick = {
                        riskToolToDisable = null
                        onToggleApproval(false)
                    },
                ) { Text(stringResource(R.string.root_approval_disable_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { riskToolToDisable = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
