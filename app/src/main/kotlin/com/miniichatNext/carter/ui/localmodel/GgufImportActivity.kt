/**
 * 项目名称: MiniiChat Next
 * 项目地址: https://github.com/director4168/MiniiChat-Next
 * 项目开源: AGPL-3.0
 * © 2026 director_Carter
 */
package com.miniichatNext.carter.ui.localmodel

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.miniichatNext.carter.MainActivity

class GgufImportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = intent
        val uri = source.data
        try {
            if (source.action == Intent.ACTION_VIEW && uri != null && (uri.scheme == "content" || uri.scheme == "file")) {
                startActivity(Intent(this, MainActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    data = uri
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    if (uri.scheme == "content") {
                        addFlags(source.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                })
            }
        } finally {
            finish()
        }
    }
}
