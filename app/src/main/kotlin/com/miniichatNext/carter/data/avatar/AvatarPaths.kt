package com.miniichatNext.carter.data.avatar

import java.io.File
import java.util.Base64

internal object AvatarPaths {
    fun encodedId(id: String): String = if (id.matches(Regex("[A-Za-z0-9]+"))) id else
        Base64.getUrlEncoder().withoutPadding().encodeToString(id.toByteArray(Charsets.UTF_8))

    fun user(filesDir: File): File = File(filesDir, "avatars/Avatars_User.jpg")
    fun assistant(filesDir: File, id: String): File =
        File(filesDir, "avatars/Avatars_Assistant/Avatars_${encodedId(id)}_Assistant.jpg")
    fun background(filesDir: File, id: String): File = File(filesDir, "avatars/Background_${encodedId(id)}.jpg")
}
