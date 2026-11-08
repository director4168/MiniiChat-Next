package com.miniichatNext.carter.data.backup

import kotlinx.serialization.Serializable

@Serializable
data class BackupFileIndex(val path: String, val size: Long, val md5: String)
