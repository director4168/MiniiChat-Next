/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.ui.components

import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.miniichatNext.carter.debug.DebugLog
import com.miniichatNext.carter.ui.theme.LocalDarkMode
import com.yalantis.ucrop.UCrop
import com.yalantis.ucrop.UCropActivity
import java.io.File


// 此裁剪器从RikkaHub项目的/components/ai/CropLauncher.kt移植而来
@Composable
fun rememberImageCropLauncher(
    onResult: (savedPath: String) -> Unit,
    onCancel: () -> Unit = {},
    onFailure: () -> Unit = onCancel,
    aspectRatio: Pair<Float, Float>? = 1f to 1f,
    outputDir: File? = null,
    outputPrefix: String = "crop_",
    maxResultSize: Int = 0
): (Uri) -> Unit {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val cropBackground = colors.background.toArgb()
    val cropSurface = colors.surface.toArgb()
    val cropContent = colors.onSurface.toArgb()
    val cropActive = colors.primary.toArgb()
    val dark = LocalDarkMode.current
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }
    var sourcePath by rememberSaveable { mutableStateOf<String?>(null) }


    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // rikkahub同款简单三态：RESULT_OK→信任本次输出文件；CANCEL→取消；其它→失败。
        // 不做decodeFile主线程校验（大图解码慢/OOM会把正确结果误判"不可用"）；
        // 不用newestCropOutput/lastOutputPath兜底（旧图正是"显示原图"的元凶）。
        val outFile = pendingPath?.let(::File)
        val sourceFile = sourcePath?.let(::File)
        pendingPath = null
        sourcePath = null

        when {
            result.resultCode == Activity.RESULT_OK && outFile != null &&
                outFile.isFile && outFile.length() > 0L -> {
                DebugLog.i("ImageCropper", "crop ok: ${outFile.absolutePath} (${outFile.length()} bytes)")
                runCatching { sourceFile?.delete() }
                onResult(outFile.absolutePath)
            }
            result.resultCode == Activity.RESULT_CANCELED -> {
                DebugLog.w("ImageCropper", "crop cancelled by user")
                runCatching { outFile?.delete() }
                runCatching { sourceFile?.delete() }
                onCancel()
            }
            else -> {
                val error = runCatching { result.data?.let { UCrop.getError(it) } }.getOrNull()
                DebugLog.e(
                    "ImageCropper",
                    "crop failed: resultCode=${result.resultCode} err=${error?.message ?: "null"}",
                    error
                )
                runCatching { outFile?.delete() }
                runCatching { sourceFile?.delete() }
                onFailure()
            }
        }
    }

    return remember(context, aspectRatio, launcher, outputDir, outputPrefix, maxResultSize, cropBackground, cropSurface, cropContent, cropActive, dark) {
        { sourceUri: Uri ->
            val dir = outputDir ?: context.cacheDir
            runCatching { if (!dir.exists()) dir.mkdirs() }
            val outFile = File(dir, outputPrefix + System.currentTimeMillis() + ".jpg")
            pendingPath = outFile.absolutePath
            val outUri = Uri.fromFile(outFile)

            // 源也要file://（rikkahub同款）：content://源在部分设备上UCrop读取异常（直通原图/写不出）。
            // 先把content://复制到cache临时文件，再交给UCrop。
            val launchSource: Uri = if (sourceUri.scheme == "content") {
                val tmp = File(context.cacheDir, "crop_src_${System.currentTimeMillis()}.jpg")
                sourcePath = tmp.absolutePath
                runCatching {
                    context.contentResolver.openInputStream(sourceUri)?.use { input ->
                        tmp.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("cannot open $sourceUri")
                    Uri.fromFile(tmp)
                }.getOrElse {
                    DebugLog.w("ImageCropper", "copy source to temp failed: ${it.message}; use original uri")
                    sourceUri
                }
            } else {
                sourcePath = null
                sourceUri
            }
            DebugLog.d("ImageCropper", "crop src=$launchSource out=$outUri")

            var crop = UCrop.of(launchSource, outUri)
            val options = UCrop.Options().apply {
                setToolbarColor(cropSurface)
                setToolbarWidgetColor(cropContent)
                setRootViewBackgroundColor(cropBackground)
                setActiveControlsWidgetColor(cropActive)
                setLogoColor(cropContent)
                // uCrop滚轮容器不读取Compose主题，单独传入实际背景与文字颜色。
                setFreeStyleCropEnabled(aspectRatio == null)
                setAllowedGestures(
                    UCropActivity.SCALE,
                    UCropActivity.ROTATE,
                    UCropActivity.NONE
                )
                setCompressionFormat(Bitmap.CompressFormat.JPEG)
                setCompressionQuality(92)
                setHideBottomControls(false)
            }
            crop = crop.withOptions(options)
            if (aspectRatio != null) {
                crop = crop.withAspectRatio(aspectRatio.first, aspectRatio.second)
            }
            if (maxResultSize > 0) {
                crop = crop.withMaxResultSize(maxResultSize, maxResultSize)
            }
            runCatching {
                val intent = crop.getIntent(context).setClass(context, ThemedCropActivity::class.java)
                    .putExtra(ThemedCropActivity.EXTRA_SURFACE, cropSurface)
                    .putExtra(ThemedCropActivity.EXTRA_CONTENT, cropContent)
                    .putExtra(ThemedCropActivity.EXTRA_DARK, dark)
                launcher.launch(intent)
            }.onFailure { e ->
                DebugLog.e("ImageCropper", "cannot start crop activity: ${e.message}", e)
                runCatching { outFile.delete() }
                runCatching { sourcePath?.let(::File)?.delete() }
                pendingPath = null
                sourcePath = null
                onFailure()
            }
        }
    }
}

