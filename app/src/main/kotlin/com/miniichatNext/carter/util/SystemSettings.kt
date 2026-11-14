/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * 打开「所有文件访问权限」设置页。
 * 部分ROM没有按包名跳转的页面（MIUI Android 10实测抛ActivityNotFoundException），
 * 逐级降级到总开关列表、应用详情页。全部失败返回false，由调用方提示。
 */
internal fun openAllFilesAccessSettings(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        // Android 10及以下没有分区存储总开关，落到应用详情页
        return openAppDetailsSettings(context)
    }
    val candidates = listOf(
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri(context)),
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
    )
    return candidates.any { context.startSettings(it) }
}

/** 应用详情页：低版本ROM上没有专用设置页时的兜底入口。 */
internal fun openAppDetailsSettings(context: Context): Boolean =
    context.startSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context)))

private fun packageUri(context: Context): Uri = Uri.parse("package:" + context.packageName)

private fun Context.startSettings(intent: Intent): Boolean =
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
