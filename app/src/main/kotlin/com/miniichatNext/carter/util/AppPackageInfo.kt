/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.util

import android.content.Context
import android.os.Build
import android.os.Process

/** 当前安装包架构，和设备可支持的最高架构分开处理。 */
internal fun currentPackageAbi(context: Context): String = resolvePackageAbi(
    context.applicationInfo.nativeLibraryDir.orEmpty(),
    Process.is64Bit(),
    Build.SUPPORTED_ABIS.toList(),
)

internal fun resolvePackageAbi(libraryDir: String, is64Bit: Boolean, supportedAbis: List<String>): String = when {
    libraryDir.contains("arm64") -> "arm64-v8a"
    libraryDir.endsWith("/arm") -> "armeabi-v7a"
    libraryDir.contains("x86_64") -> "x86_64"
    libraryDir.endsWith("/x86") -> "x86"
    else -> supportedAbis.firstOrNull { it.contains("64") == is64Bit }.orEmpty()
}
