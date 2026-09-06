package com.miniichatNext.carter.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = AvatarSerializer::class)
sealed class Avatar {
    @Serializable
    data object None : Avatar()

    @Serializable
    data class Emoji(val content: String) : Avatar()

    @Serializable
    data class Image(val path: String) : Avatar()

    companion object {
        fun fromLegacy(raw: String, path: String? = null): Avatar {
            if (!path.isNullOrBlank()) return Image(path)
            if (raw.isBlank()) return None
            return Emoji(raw)
        }
    }
}

object AvatarSerializer : KSerializer<Avatar> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.miniichatNext.carter.data.Avatar", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Avatar) {
        when (value) {
            is Avatar.None -> encoder.encodeString("")
            is Avatar.Emoji -> encoder.encodeString("emoji:${value.content}")
            is Avatar.Image -> encoder.encodeString("image:${value.path}")
        }
    }

    override fun deserialize(decoder: Decoder): Avatar {
        val raw = decoder.decodeString()
        if (raw.isBlank()) return Avatar.None
        return when {
            raw.startsWith("image:") -> Avatar.Image(raw.removePrefix("image:"))
            raw.startsWith("emoji:") -> Avatar.Emoji(raw.removePrefix("emoji:"))
            else -> Avatar.Emoji(raw)
        }
    }
}
