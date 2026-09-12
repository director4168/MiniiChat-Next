package com.miniichatNext.carter

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.miniichatNext.carter.ui.AppRoot
import com.miniichatNext.carter.ui.theme.MiniiChatTheme
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val vm: ChatViewModel by viewModels()

    override fun attachBaseContext(newBase: Context?) {
        if (newBase == null) { super.attachBaseContext(null); return }
        val lang = newBase.getSharedPreferences("locale_cache", MODE_PRIVATE)
            .getString("language", "system") ?: "system"
        val ctx = if (lang == "system") newBase else applyLocale(newBase, lang)
        super.attachBaseContext(ctx)
    }

    private fun applyLocale(base: Context, lang: String): Context {
        val locale = when (lang) {
            "zh" -> Locale.SIMPLIFIED_CHINESE
            "en" -> Locale.ENGLISH
            else -> Locale.getDefault()
        }
        Locale.setDefault(locale)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(locale)
        return base.createConfigurationContext(cfg)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.miniichatNext.carter.Debug.DebugLog.i(
            "Activity", "MainActivity.onCreate (savedState=${savedInstanceState != null})"
        )
        enableEdgeToEdge()
        setContent {
            val s by vm.settings.collectAsState()

            val prefs = getSharedPreferences("locale_cache", MODE_PRIVATE)
            val persisted = prefs.getString("language", "system") ?: "system"

            var primed by remember { mutableStateOf(false) }
            var prevLang by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(s.language) {
                if (!primed) {
                    primed = true
                    prevLang = s.language
                    return@LaunchedEffect
                }
                if (s.language != prevLang) {
                    if (s.language != persisted) {
                        prefs.edit().putString("language", s.language).apply()
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.language_restart_toast),
                            Toast.LENGTH_LONG
                        ).show()
                        recreate()
                    }
                    prevLang = s.language
                }
            }

            MiniiChatTheme(themeMode = s.themeMode, dynamicColor = s.dynamicColor) {
                AppRoot(vm)
            }
        }
    }
}
