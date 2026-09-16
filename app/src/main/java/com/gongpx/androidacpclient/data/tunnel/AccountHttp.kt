package com.gongpx.androidacpclient.data.tunnel

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

private class AccountRequestError(message: String) : IOException(message)

internal class AccountHttp {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS).build()

    fun form(url: String, values: Map<String, String>): JSONObject =
        request(url, body = FormBody.Builder().apply { values.forEach { (key, value) -> add(key, value) } }.build(), oauth = true)

    fun json(url: String, body: JSONObject, headers: Map<String, String>): JSONObject =
        request(url, headers, body.toString().toRequestBody("application/json".toMediaType()))

    fun request(url: String, headers: Map<String, String> = emptyMap(), body: RequestBody? = null, oauth: Boolean = false): JSONObject {
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .header("User-Agent", "AgentLink").apply {
                headers.forEach { (key, value) -> header(key, value) }
                if (body != null) post(body)
            }.build()
        try {
            client.newCall(request).execute().use { response ->
                val text = response.peekBody(2_000_001).string()
                if (text.length > 2_000_000) throw AccountRequestError("Account service response is too large.")
                val json = try { JSONObject(text) } catch (_: org.json.JSONException) {
                    throw AccountRequestError("Account service returned an invalid response (HTTP ${response.code}).")
                }
                if (!response.isSuccessful && !(oauth && json.has("error"))) {
                    if (response.code in setOf(401, 403)) throw TunnelLoginRequired()
                    throw AccountRequestError(when (response.code) {
                        429 -> if (url.endsWith("/pairing/request")) "Finish or dismiss the pending computer confirmation, then retry."
                            else "Too many requests. Wait and retry."
                        503 -> "The bridge could not save pairing credentials. Check its console."
                        404 -> "This bridge does not support account pairing. Update the bridge or use QR pairing."
                        else -> "Account or bridge request failed (HTTP ${response.code})."
                    })
                }
                return json
            }
        } catch (error: TunnelLoginRequired) {
            throw error
        } catch (error: AccountRequestError) {
            throw error
        } catch (_: IOException) {
            // Network exceptions may contain credential-bearing URLs.
            throw IOException("Could not reach the account service or bridge. Check the network and retry.")
        }
    }
}
