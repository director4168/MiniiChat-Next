/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.data.update

import java.io.File
import java.io.IOException

/** 仅完整传输后验证失败保留文件，网络中断不走强制安装。 */
internal class UpdateVerificationFailure(val file: File, cause: Exception) : IOException(cause.message, cause)
