package com.gongpx.androidacpclient.data.model

import java.io.IOException
import org.json.JSONObject

data class ToolDetailPage(
    val ref: String,
    val snapshot: String,
    val version: Int,
    val offset: Int,
    val text: String,
    val next: Int?,
) {
    fun toJson(): JSONObject = JSONObject().put("ref", ref).put("snapshot", snapshot)
        .put("version", version).put("offset", offset).put("text", text)
        .put("next", next ?: JSONObject.NULL).put("complete", next == null)

    companion object {
        fun parse(data: JSONObject): ToolDetailPage {
            val page = ToolDetailPage(data.getString("ref"), data.getString("snapshot"),
                data.getInt("version"), data.getInt("offset"), data.getString("text"),
                if (data.isNull("next")) null else data.getInt("next"))
            if (!page.ref.matches(Regex("[a-f0-9]{48}")) || !page.snapshot.matches(Regex("[a-f0-9]{48}")) ||
                page.version < 1 || page.offset < 0 || page.text.length > 8192 ||
                (page.next != null && page.next <= page.offset)
            ) throw IOException("Invalid tool detail page.")
            return page
        }
    }
}
