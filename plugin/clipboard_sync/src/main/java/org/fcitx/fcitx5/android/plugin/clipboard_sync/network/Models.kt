package org.fcitx.fcitx5.android.plugin.clipboard_sync.network

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ClipboardData(
    @SerialName("Type")
    @JsonNames("type")
    val type: String = "Text",

    @SerialName("Text")
    @JsonNames("text", "Clipboard")
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
