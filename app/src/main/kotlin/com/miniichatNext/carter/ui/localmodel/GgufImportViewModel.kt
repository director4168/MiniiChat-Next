/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.ui.localmodel

import androidx.lifecycle.ViewModel
import com.miniichatNext.carter.data.localmodel.ExternalGgufRequests

internal class GgufImportViewModel : ViewModel() {
    val requests = ExternalGgufRequests()
}
