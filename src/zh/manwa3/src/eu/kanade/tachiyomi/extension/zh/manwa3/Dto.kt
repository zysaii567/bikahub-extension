package eu.kanade.tachiyomi.extension.zh.manwa3

import kotlinx.serialization.Serializable

@Serializable
class ImageResponseDto(
    val code: Int = 0,
    val data: ImageDataDto? = null,
)

@Serializable
class ImageDataDto(
    val images: List<ImageItemDto> = emptyList(),
)

@Serializable
class ImageItemDto(
    val url: String = "",
)
