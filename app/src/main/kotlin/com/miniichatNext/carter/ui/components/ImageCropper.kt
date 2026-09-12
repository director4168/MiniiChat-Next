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
import com.miniichatNext.carter.Debug.DebugLog
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

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val fromResult = runCatching { result.data?.let { UCrop.getOutput(it) } }.getOrNull()
        val candidates = mutableListOf<File>()
        fromResult?.path?.let { candidates.add(File(it)) }
        pendingFile?.let { candidates.add(it) }
        if (lastOutputPath.isNotBlank()) candidates.add(File(lastOutputPath))
        pendingFile = null

        val usable = candidates.firstOrNull { it.isFile && it.length() > 0L }
        if (usable != null) {
            DebugLog.i(
                "ImageCropper",
                "crop ok: path=${usable.absolutePath} bytes=${usable.length()} " +
                    "src=${if (fromResult != null) "result" else "remembered"}"
            )
            onResult(usable.absolutePath)
            return@rememberLauncherForActivityResult
        }

        if (result.resultCode == Activity.RESULT_CANCELED) {
            DebugLog.w("ImageCropper", "crop cancelled by user (resultCode=0)")
            candidates.forEach { runCatching { it.delete() } }
            onCancel()
            return@rememberLauncherForActivityResult
        }

        val error = runCatching { result.data?.let { UCrop.getError(it) } }.getOrNull()
        DebugLog.e(
            "ImageCropper",
            "crop failed: resultCode=${result.resultCode} " +
                "uri=${fromResult ?: lastOutputPath} err=${error?.message ?: "null"}",
            error
        )
        val fallback = newestCropOutput(context, outputDir, outputPrefix)
        if (fallback != null) {
            DebugLog.w(
                "ImageCropper", "using newest file in output dir: ${fallback.absolutePath}"
            )
            onResult(fallback.absolutePath)
        } else {
            candidates.forEach { runCatching { it.delete() } }
            onFailure()
        }
    }

    return remember(context, aspectRatio, launcher, outputDir, outputPrefix, maxResultSize) {
        { sourceUri: Uri ->
            val dir = outputDir ?: context.cacheDir
            runCatching { if (!dir.exists()) dir.mkdirs() }
            val outFile = File(dir, outputPrefix + System.currentTimeMillis() + ".jpg")
            lastOutputPath = outFile.absolutePath
            pendingFile = outFile

            val outUri = fileProviderUri(context, outFile) ?: Uri.fromFile(outFile)
            DebugLog.d("ImageCropper", "crop output uri=$outUri (file=${outFile.absolutePath})")

            var crop = UCrop.of(sourceUri, outUri)
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
                onFailure()
            }
        }
    }
}

private fun fileProviderUri(context: Context, file: File): Uri? = runCatching {
    androidx.core.content.FileProvider.getUriForFile(
        context,
        context.packageName + ".fileprovider",
        file
    )
}.onFailure { e ->
    DebugLog.w("ImageCropper", "FileProvider uri unavailable, falling back to file:// : ${e.message}")
}.getOrNull()

private fun newestCropOutput(context: Context, outputDir: File?, prefix: String): File? = runCatching {
    val dir = outputDir ?: context.cacheDir
    dir.listFiles { f -> f.isFile && f.name.startsWith(prefix) && f.length() > 0L }
        ?.maxByOrNull { it.lastModified() }
        ?.takeIf { System.currentTimeMillis() - it.lastModified() < 5 * 60 * 1000L }
}.getOrNull()
