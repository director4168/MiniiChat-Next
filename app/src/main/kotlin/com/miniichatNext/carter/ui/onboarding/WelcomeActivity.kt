package com.miniichatNext.carter.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.miniichatNext.carter.MainActivity
import com.miniichatNext.carter.R
import com.miniichatNext.carter.data.avatar.Avatar
import com.miniichatNext.carter.data.avatar.AvatarStorage
import com.miniichatNext.carter.data.model.ProviderConfig
import com.miniichatNext.carter.data.model.ProviderType
import com.miniichatNext.carter.data.model.UserProfile
import com.miniichatNext.carter.data.model.UserProfileStore
import com.miniichatNext.carter.data.store.ProviderStore
import com.miniichatNext.carter.ui.components.AvatarPicker
import com.miniichatNext.carter.ui.components.AvatarView
import com.miniichatNext.carter.ui.theme.MiniiChatTheme
import com.miniichatNext.carter.util.newId
import kotlinx.coroutines.launch

private const val ONBOARDING_PREFS = "onboarding_pref"
private const val ONBOARDING_DONE = "has_initialized"

private enum class OnboardingPage {
    INTRO,
    PROFILE,
    PROVIDER,
    PERMISSIONS,
}

private data class PermissionSnapshot(
    val storage: Boolean,
    val calendar: Boolean,
    val overlay: Boolean,
    val media: Boolean,
    val install: Boolean,
) {
    val allGranted: Boolean
        get() = storage && calendar && overlay && media && install
}

class WelcomeActivity : ComponentActivity() {
    private val permissionVersion = mutableStateOf(0)

    private val runtimePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionVersion.value++
    }

    private val specialPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        permissionVersion.value++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })

        val profileStore = UserProfileStore(this)
        val providerStore = ProviderStore(this)
        setContent {
            MiniiChatTheme {
                val version by permissionVersion
                WelcomeFlowScreen(
                    profileStore = profileStore,
                    providerStore = providerStore,
                    permissionVersion = version,
                    onRequestPermission = ::requestPermission,
                    onFinish = ::finishOnboarding,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permissionVersion.value++
    }

    private fun finishOnboarding() {
        getSharedPreferences(ONBOARDING_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ONBOARDING_DONE, true)
            .apply()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun requestPermission(kind: String) {
        when (kind) {
            "storage" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                openSpecialPermission(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            } else {
                runtimePermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    )
                )
            }
            "calendar" -> runtimePermissionLauncher.launch(
                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            )
            "media" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runtimePermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VIDEO,
                        Manifest.permission.READ_MEDIA_AUDIO,
                    )
                )
            } else {
                runtimePermissionLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
            }
            "overlay" -> openSpecialPermission(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            "install" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                openSpecialPermission(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            }
        }
    }

    private fun openSpecialPermission(action: String) {
        val intent = Intent(action).apply {
            data = Uri.parse("package:$packageName")
        }
        runCatching { specialPermissionLauncher.launch(intent) }
            .onFailure { permissionVersion.value++ }
    }
}

@Composable
private fun WelcomeFlowScreen(
    profileStore: UserProfileStore,
    providerStore: ProviderStore,
    permissionVersion: Int,
    onRequestPermission: (String) -> Unit,
    onFinish: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf(UserProfile()) }
    var providers by remember { mutableStateOf(emptyList<ProviderConfig>()) }
    var loaded by remember { mutableStateOf(false) }
    var pageIndex by remember { mutableStateOf(0) }
    var profileName by remember { mutableStateOf("") }
    var profileAvatar by remember { mutableStateOf<Avatar>(Avatar.Emoji("🙂")) }
    var providerName by remember { mutableStateOf("") }
    var providerBaseUrl by remember { mutableStateOf("") }
    var providerApiKey by remember { mutableStateOf("") }
    var providerType by remember { mutableStateOf(ProviderType.OPENAI) }
    var showAvatarPicker by remember { mutableStateOf(false) }
    var profileError by remember { mutableStateOf(false) }
    var providerError by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        profile = profileStore.snapshot()
        providers = providerStore.snapshot()
        profileName = profile.displayName.takeUnless { it == "You" }.orEmpty()
        profileAvatar = profile.avatar
        loaded = true
    }

    val permissionSnapshot = remember(permissionVersion) { readPermissionSnapshot(context) }
    val pages = remember(profile, providers, permissionSnapshot) {
        buildList {
            add(OnboardingPage.INTRO)
            if (!profileConfigured(profile)) add(OnboardingPage.PROFILE)
            if (providers.isEmpty()) add(OnboardingPage.PROVIDER)
            if (!permissionSnapshot.allGranted) add(OnboardingPage.PERMISSIONS)
        }
    }
    LaunchedEffect(pages.size) {
        pageIndex = pageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
    }

    if (!loaded) {
        Box(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.CircularProgressIndicator()
        }
        return
    }

    val page = pages[pageIndex.coerceIn(0, pages.lastIndex)]
    val isFirst = pageIndex == 0
    val isLast = pageIndex == pages.lastIndex
    val stepText = stringResource(R.string.onboarding_step, pageIndex + 1, pages.size)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = page,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "onboarding_page",
        ) { currentPage ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f),
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                tonalElevation = 4.dp,
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    OnboardingHeader(
                        page = currentPage,
                        stepText = stepText,
                        canSkip = currentPage != OnboardingPage.INTRO,
                        onSkip = {
                            if (isLast) onFinish() else pageIndex = (pageIndex + 1).coerceAtMost(pages.lastIndex)
                        },
                    )
                    Spacer(Modifier.height(16.dp))
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        when (currentPage) {
                            OnboardingPage.INTRO -> IntroContent()
                            OnboardingPage.PROFILE -> ProfileContent(
                                name = profileName,
                                avatar = profileAvatar,
                                error = profileError,
                                onNameChange = { profileName = it; profileError = false },
                                onAvatarClick = { showAvatarPicker = true },
                            )
                            OnboardingPage.PROVIDER -> ProviderContent(
                                name = providerName,
                                baseUrl = providerBaseUrl,
                                apiKey = providerApiKey,
                                type = providerType,
                                error = providerError,
                                onNameChange = { providerName = it; providerError = false },
                                onBaseUrlChange = { providerBaseUrl = it; providerError = false },
                                onApiKeyChange = { providerApiKey = it },
                                onTypeChange = { providerType = it },
                            )
                            OnboardingPage.PERMISSIONS -> PermissionsContent(
                                snapshot = permissionSnapshot,
                                onRequest = onRequestPermission,
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (isFirst) Spacer(Modifier.width(1.dp))
                        else OutlinedButton(onClick = { pageIndex = (pageIndex - 1).coerceAtLeast(0) }) {
                            Text(stringResource(R.string.onboarding_previous))
                        }
                        val actionLabel = when {
                            isLast -> stringResource(R.string.onboarding_enter)
                            else -> stringResource(R.string.onboarding_next)
                        }
                        Button(
                            enabled = !saving,
                            onClick = {
                                when (currentPage) {
                                    OnboardingPage.PROFILE -> {
                                        val trimmed = profileName.trim()
                                        if (trimmed.isBlank()) {
                                            profileError = true
                                        } else {
                                            saving = true
                                            val updated = profile.copy(displayName = trimmed, avatar = profileAvatar)
                                            scope.launch {
                                                profileStore.save(updated)
                                                profile = updated
                                                saving = false
                                                pageIndex = (pageIndex + 1).coerceAtMost(pages.lastIndex)
                                            }
                                        }
                                    }
                                    OnboardingPage.PROVIDER -> {
                                        val name = providerName.trim()
                                        val base = providerBaseUrl.trim().trimEnd('/')
                                        if (name.isBlank() || base.isBlank()) {
                                            providerError = true
                                        } else {
                                            saving = true
                                            val provider = ProviderConfig(
                                                id = newId(),
                                                name = name,
                                                baseUrl = base,
                                                apiKey = providerApiKey.trim(),
                                                providerType = providerType.name,
                                            )
                                            scope.launch {
                                                providerStore.upsert(provider)
                                                providers = providerStore.snapshot()
                                                saving = false
                                                pageIndex = (pageIndex + 1).coerceAtMost(pages.lastIndex)
                                            }
                                        }
                                    }
                                    else -> if (isLast) onFinish()
                                    else pageIndex = (pageIndex + 1).coerceAtMost(pages.lastIndex)
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        ) {
                            Text(actionLabel, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    if (showAvatarPicker) {
        AvatarPicker(
            current = profileAvatar,
            canonicalPath = AvatarStorage.userAvatarFile(context).absolutePath,
            onChange = { profileAvatar = it; showAvatarPicker = false },
            onDismiss = { showAvatarPicker = false },
        )
    }
}

@Composable
private fun OnboardingHeader(
    page: OnboardingPage,
    stepText: String,
    canSkip: Boolean,
    onSkip: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(
                    when (page) {
                        OnboardingPage.INTRO -> R.string.onboarding_welcome
                        OnboardingPage.PROFILE -> R.string.onboarding_profile_title
                        OnboardingPage.PROVIDER -> R.string.onboarding_provider_title
                        OnboardingPage.PERMISSIONS -> R.string.onboarding_permission_title
                    }
                ),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(stepText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (canSkip) TextButton(onClick = onSkip) { Text(stringResource(R.string.onboarding_skip)) }
    }
}

@Composable
private fun IntroContent() {
    OnboardingIcon(Icons.Default.Star, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
    Spacer(Modifier.height(18.dp))
    Text(
        stringResource(R.string.onboarding_intro_title),
        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(10.dp))
    Text(
        stringResource(R.string.onboarding_intro_body),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(24.dp))
    InfoCard(Icons.Default.Cloud, R.string.onboarding_intro_provider)
    InfoCard(Icons.Default.Folder, R.string.onboarding_intro_workspace)
    InfoCard(Icons.Default.Security, R.string.onboarding_intro_tools)
}

@Composable
private fun ProfileContent(
    name: String,
    avatar: Avatar,
    error: Boolean,
    onNameChange: (String) -> Unit,
    onAvatarClick: () -> Unit,
) {
    OnboardingIcon(Icons.Default.Visibility, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
    Spacer(Modifier.height(12.dp))
    Text(
        stringResource(R.string.onboarding_profile_hint),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(22.dp))
    Box(Modifier.size(88.dp).clip(CircleShape).clickable(onClick = onAvatarClick)) {
        AvatarView(avatar = avatar, fallbackInitial = name.take(1).ifBlank { "U" }, size = 88.dp)
    }
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.onboarding_avatar_hint), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
    Spacer(Modifier.height(22.dp))
    OnboardingField(
        label = stringResource(R.string.onboarding_name_label),
        value = name,
        hint = stringResource(R.string.onboarding_name_hint),
        error = error,
        errorText = stringResource(R.string.onboarding_name_error),
        onValueChange = onNameChange,
    )
}

@Composable
private fun ProviderContent(
    name: String,
    baseUrl: String,
    apiKey: String,
    type: ProviderType,
    error: Boolean,
    onNameChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onTypeChange: (ProviderType) -> Unit,
) {
    OnboardingIcon(Icons.Default.Cloud, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
    Spacer(Modifier.height(12.dp))
    Text(
        stringResource(R.string.onboarding_provider_hint),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(18.dp))
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface).padding(3.dp),
    ) {
        ProviderType.entries.forEach { item ->
            val selected = item == type
            Box(
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(
                    if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                ).clickable { onTypeChange(item) }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(if (item == ProviderType.OPENAI) R.string.provider_type_openai else R.string.provider_type_claude),
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Spacer(Modifier.height(14.dp))
    OnboardingField(stringResource(R.string.onboarding_provider_name), name, stringResource(R.string.onboarding_provider_name_hint), error, stringResource(R.string.onboarding_provider_error), onNameChange)
    Spacer(Modifier.height(12.dp))
    OnboardingField(stringResource(R.string.onboarding_provider_base_url), baseUrl, "https://api.example.com/v1", error, stringResource(R.string.onboarding_provider_error), onBaseUrlChange)
    Spacer(Modifier.height(12.dp))
    OnboardingField(stringResource(R.string.onboarding_provider_api_key), apiKey, stringResource(R.string.onboarding_provider_api_key_hint), false, "", onApiKeyChange, password = true)
    if (error) Text(stringResource(R.string.onboarding_provider_error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun PermissionsContent(snapshot: PermissionSnapshot, onRequest: (String) -> Unit) {
    OnboardingIcon(
        if (snapshot.allGranted) Icons.Default.CheckCircle else Icons.Default.Security,
        if (snapshot.allGranted) Color(0xFF2E7D32).copy(alpha = 0.18f) else MaterialTheme.colorScheme.primaryContainer,
        if (snapshot.allGranted) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onPrimaryContainer,
    )
    Spacer(Modifier.height(12.dp))
    Text(stringResource(R.string.onboarding_permission_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    Spacer(Modifier.height(16.dp))
    PermissionRow(Icons.Default.Folder, R.string.onboarding_permission_storage, R.string.onboarding_permission_storage_desc, snapshot.storage, "storage", onRequest)
    PermissionRow(Icons.Default.CalendarMonth, R.string.onboarding_permission_calendar, R.string.onboarding_permission_calendar_desc, snapshot.calendar, "calendar", onRequest)
    PermissionRow(Icons.Default.Visibility, R.string.onboarding_permission_overlay, R.string.onboarding_permission_overlay_desc, snapshot.overlay, "overlay", onRequest)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        PermissionRow(Icons.Default.Visibility, R.string.onboarding_permission_media, R.string.onboarding_permission_media_desc, snapshot.media, "media", onRequest)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        PermissionRow(Icons.Default.Settings, R.string.onboarding_permission_install, R.string.onboarding_permission_install_desc, snapshot.install, "install", onRequest)
    }
}

@Composable
private fun PermissionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    titleRes: Int,
    descRes: Int,
    granted: Boolean,
    kind: String,
    onRequest: (String) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = if (granted) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(titleRes),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(3.dp))
                Text(stringResource(descRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (granted) {
                Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF2E7D32), modifier = Modifier.size(22.dp))
            } else {
                TextButton(onClick = { onRequest(kind) }) { Text(stringResource(R.string.onboarding_permission_grant)) }
            }
        }
    }
}

@Composable
private fun OnboardingIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, background: Color, tint: Color) {
    Box(Modifier.size(58.dp).clip(CircleShape).background(background), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(30.dp))
    }
}

@Composable
private fun InfoCard(icon: androidx.compose.ui.graphics.vector.ImageVector, textRes: Int) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(textRes),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun OnboardingField(
    label: String,
    value: String,
    hint: String,
    error: Boolean,
    errorText: String,
    onValueChange: (String) -> Unit,
    password: Boolean = false,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface)
                .border(1.dp, if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            if (value.isBlank()) Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            )
        }
        if (error) Text(errorText, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
    }
}

private fun profileConfigured(profile: UserProfile): Boolean =
    profile.displayName.isNotBlank() && profile.displayName != "You" &&
        profile.avatar != Avatar.Emoji("🙂")

private fun readPermissionSnapshot(context: Context): PermissionSnapshot {
    val storage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)
    }
    val calendar = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
    val overlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
    val media = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
    } else true
    val install = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()
    return PermissionSnapshot(storage, calendar, overlay, media, install)
}
