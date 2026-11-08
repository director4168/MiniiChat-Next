package com.miniichatNext.carter.data.backup

import kotlinx.serialization.Serializable

@Serializable
data class BackupManifest(
    val formatVersion: Int = 1,
    val appVersion: String,
    val createdAt: Long,
    val files: List<BackupFileIndex>,
    val missingFiles: List<String> = emptyList(),
    val missingFileCount: Int = missingFiles.size,
    val encrypted: Boolean = false,
    val backupMode: String? = null,
    val credentialKey: String? = null,

)
