/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment

/**
 * 公共存储是否可用。
 * 11+看「所有文件访问」，10只有READ_EXTERNAL_STORAGE，9及以下还要WRITE（清单里WRITE限到28）。
 */
internal fun hasStorageAccess(context: Context): Boolean = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
        runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
        granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
    else -> granted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
}

/** 该版本真正能申请的存储权限；11+只能去系统设置开，故返回空。 */
internal fun storagePermissionNames(): Array<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> emptyArray()
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    else -> arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
    )
}

private fun granted(context: Context, permission: String): Boolean =
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
