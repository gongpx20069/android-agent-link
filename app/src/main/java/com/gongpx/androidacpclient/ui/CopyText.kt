package com.gongpx.androidacpclient.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

internal const val MAX_CLIPBOARD_CHARACTERS = 128 * 1024

internal fun copyPlainText(context: Context, text: String) {
    require(text.length <= MAX_CLIPBOARD_CHARACTERS) { "Text exceeds clipboard safety limit" }
    val clipboard = checkNotNull(context.getSystemService(ClipboardManager::class.java))
    clipboard.setPrimaryClip(ClipData.newPlainText("AgentLink", text))
}
