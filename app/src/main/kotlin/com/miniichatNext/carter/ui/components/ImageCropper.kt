package com.miniichatNext.carter.ui.components

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.miniichatNext.carter.debug.DebugLog
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
    var lastOutputPath by rememberSaveable { mutableStateOf("") }
    var pendingFile by remember { mutableStateOf<File?>(null) }
    var pendingSourceFile by remember { mutableStateOf<File?>(null) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // rikkahub同款简单三态：RESULT_OK→信任本次输出文件；CANCEL→取消；其它→失败。
        // 不做decodeFile主线程校验（大图解码慢/OOM会把正确结果误判"不可用"）；
        // 不用newestCropOutput/lastOutputPath兜底（旧图正是"显示原图"的元凶）。
        val outFile = pendingFile
        val sourceFile = pendingSourceFile
        pendingFile = null
        pendingSourceFile = null

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

    return remember(context, aspectRatio, launcher, outputDir, outputPrefix, maxResultSize) {
        { sourceUri: Uri ->
            val dir = outputDir ?: context.cacheDir
            runCatching { if (!dir.exists()) dir.mkdirs() }
            val outFile = File(dir, outputPrefix + System.currentTimeMillis() + ".jpg")
            lastOutputPath = outFile.absolutePath
            pendingFile = outFile
            val outUri = Uri.fromFile(outFile)

            // 源也要file://（rikkahub同款）：content://源在部分设备上UCrop读取异常（直通原图/写不出）。
            // 先把content://复制到cache临时文件，再交给UCrop。
            val launchSource: Uri = if (sourceUri.scheme == "content") {
                val tmp = File(context.cacheDir, "crop_src_${System.currentTimeMillis()}.jpg")
                pendingSourceFile = tmp
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
                pendingSourceFile = null
                sourceUri
            }
            DebugLog.d("ImageCropper", "crop src=$launchSource out=$outUri")

            var crop = UCrop.of(launchSource, outUri)
            val options = UCrop.Options().apply {
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
            runCatching { launcher.launch(crop.getIntent(context)) }.onFailure { e ->
                DebugLog.e("ImageCropper", "cannot start crop activity: ${e.message}", e)
                runCatching { outFile.delete() }
                runCatching { pendingSourceFile?.delete() }
                pendingFile = null
                pendingSourceFile = null
                onFailure()
            }
        }
    }
}

private fun newestCropOutput(context: Context, outputDir: File?, prefix: String): File? = runCatching {
    val dir = outputDir ?: context.cacheDir
    dir.listFiles { f -> f.isFile && f.name.startsWith(prefix) && f.length() > 0L }
        ?.maxByOrNull { it.lastModified() }
        ?.takeIf { System.currentTimeMillis() - it.lastModified() < 5 * 60 * 1000L }
}.getOrNull()
