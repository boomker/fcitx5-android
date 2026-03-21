package org.fcitx.fcitx5.android.plugin.clipboard_sync.network

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ClipboardData(
    @JsonNames("id")
    val id: String = "",

    @SerialName("Type")
    @JsonNames("type")
    val type: String = "Text",

    @SerialName("Text")
    @JsonNames("text", "Clipboard", "value")
    val text: String = "",

    @SerialName("Hash")
    @JsonNames("hash")
    val hash: String = "",

    @SerialName("HasData")
    @JsonNames("hasData")
    val hasData: Boolean = false,

    @SerialName("DataName")
    @JsonNames("dataName", "File")
    val dataName: String = "",

    @SerialName("Size")
    @JsonNames("size")
    val size: Long = 0
)

@Serializable
data class OneClipHistoryResponse(
    val items: List<OneClipHistoryItem> = emptyList()
)

@Serializable
data class OneClipHistoryItem(
    val id: String = "",
    val type: String = "text",
    val preview: String = "",
    val hasImage: Boolean = false,
    val timestamp: Double = 0.0
)

@Serializable
data class OneClipUploadTextRequest(
    val text: String
)

@Serializable
data class OneClipUploadImageRequest(
    val image: String
)

@Serializable
data class OneClipUploadResponse(
    val status: String = "",
    val message: String = ""
)
