package com.gongpx.androidacpclient.data.pairing

import com.gongpx.androidacpclient.data.model.Chat
import com.gongpx.androidacpclient.data.model.Machine
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

// Deliberately not a data class: credentials must never appear in generated toString output.
internal class TransferBundle(val machines: List<Machine>, val chats: List<Chat>)

internal object TransferLink {
    const val PREFIX = "acpclient://transfer#"
    const val MAX_LINK_CHARS = 180_000
    private const val MAX_ITEMS = 200
    private const val MAX_BYTES = 128_000
    private val aad = "AgentLink transfer v1".toByteArray(Charsets.UTF_8)

    fun encrypt(bundle: TransferBundle, password: String): String {
        require(password.length in 12..256) { "Use a transfer password with 12–256 characters." }
        val json = JSONObject().put("version", 1)
            .put("machines", JSONArray(bundle.machines.map { machine ->
                JSONObject().put("id", machine.id).put("name", machine.displayName)
                    .put("endpoint", machine.endpoint).put("token", machine.deviceToken)
                    .put("fingerprint", machine.bridgeFingerprint).put("headers", JSONObject(machine.connectionHeaders))
            }))
            .put("chats", JSONArray(bundle.chats.map { chat ->
                JSONObject().put("id", chat.id).put("title", chat.title).put("machineId", chat.machineId)
                    .put("workspaceId", chat.workspaceId).put("workspaceName", chat.workspaceName)
                    .put("workspacePath", chat.workspacePath).put("agentId", chat.agentId).put("agentName", chat.agentName)
                    .put("sessionId", chat.acpSessionId).put("resumable", chat.acpSessionResumable)
                    .put("historyReplaySupported", chat.historyReplaySupported)
                    .put("titleRevision", chat.titleRevision).put("titleGeneration", chat.titleGeneration)
            }))
        decodeBundle(json)
        val plain = json.toString().toByteArray(Charsets.UTF_8)
        require(plain.size <= MAX_BYTES) { "Too much data for a transfer link. Select fewer machines/chats." }
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return PREFIX + listOf("1", encode(salt), encode(iv), encode(cipher.doFinal(plain))).joinToString(".")
    }

    fun decrypt(link: String, password: String): TransferBundle {
        require(link.length <= MAX_LINK_CHARS && link.startsWith(PREFIX)) { "Invalid or oversized AgentLink transfer link." }
        require(password.length in 12..256) { "Enter the 12–256 character transfer password." }
        val parts = link.removePrefix(PREFIX).split('.')
        require(parts.size == 4 && parts[0] == "1") { "Unsupported transfer link version." }
        val salt = Base64.getUrlDecoder().decode(parts[1])
        val iv = Base64.getUrlDecoder().decode(parts[2])
        val encrypted = Base64.getUrlDecoder().decode(parts[3])
        require(salt.size == 16 && iv.size == 12 && encrypted.size in 16..MAX_BYTES + 16) { "Invalid transfer link." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        val plain = cipher.doFinal(encrypted)
        return decodeBundle(JSONObject(String(plain, Charsets.UTF_8)))
    }

    fun validateImport(bundle: TransferBundle, machines: List<Machine>, chats: List<Chat>, removed: (String) -> Boolean) {
        for (incoming in bundle.machines) {
            machines.firstOrNull { it.id == incoming.id }?.let { existing ->
                require(existing.bridgeFingerprint == incoming.bridgeFingerprint && existing.endpoint == incoming.endpoint) {
                    "A saved machine has the same ID but a different identity or endpoint. Remove it explicitly before importing."
                }
            }
            require(machines.none { it.id != incoming.id && it.endpoint == incoming.endpoint }) {
                "This endpoint is already saved under another machine ID."
            }
        }
        for (incoming in bundle.chats) {
            require(!removed(incoming.id)) { "A transferred chat was deleted on this phone; it cannot be restored by import." }
            chats.firstOrNull { it.id == incoming.id }?.let { existing ->
                require(existing.machineId == incoming.machineId && existing.workspacePath == incoming.workspacePath &&
                    existing.agentId == incoming.agentId) { "A transferred chat conflicts with an existing chat." }
            }
        }
    }

    private fun decodeBundle(json: JSONObject): TransferBundle {
        require(json.getInt("version") == 1) { "Unsupported transfer payload." }
        val machineRows = json.getJSONArray("machines")
        val chatRows = json.getJSONArray("chats")
        require(machineRows.length() in 1..MAX_ITEMS && chatRows.length() <= MAX_ITEMS) { "Invalid transfer item count." }
        val machines = List(machineRows.length()) { index ->
            val row = machineRows.getJSONObject(index)
            val endpoint = row.text("endpoint", 2048)
            validateEndpoint(endpoint)
            val headers = row.getJSONObject("headers")
            require(headers.length() <= 1) { "Unsupported connection headers." }
            val values = headers.keys().asSequence().associateWith { name ->
                require(name == "X-Tunnel-Authorization") { "Unsupported connection header." }
                headers.text(name, 16384).also {
                    require(it.startsWith("tunnel ") && it.length > 7 && '\r' !in it && '\n' !in it) { "Invalid tunnel authorization." }
                }
            }
            val token = row.text("token", 8192)
            require(token.none { it.isWhitespace() || it.isISOControl() }) { "Invalid device token." }
            val fingerprint = row.text("fingerprint", 128)
            require(fingerprint.matches(Regex("sha256:[a-fA-F0-9]{64}"))) { "Invalid bridge fingerprint." }
            Machine(row.text("id"), row.text("name"), endpoint, token, fingerprint, values)
        }
        require(machines.map { it.id }.toSet().size == machines.size &&
            machines.map { it.endpoint }.toSet().size == machines.size) { "Duplicate machines in transfer." }
        val chats = List(chatRows.length()) { index ->
            val row = chatRows.getJSONObject(index)
            val machine = requireNotNull(machines.find { it.id == row.text("machineId") }) { "Chat machine is missing." }
            Chat(
                id = row.text("id"), title = row.text("title"), machineId = machine.id, machineName = machine.displayName,
                workspaceId = row.text("workspaceId"), workspaceName = row.text("workspaceName"),
                workspacePath = row.text("workspacePath", 4096), agentId = row.text("agentId"), agentName = row.text("agentName"),
                createdAtMillis = System.currentTimeMillis(),
                acpSessionId = if (row.has("sessionId") && !row.isNull("sessionId")) row.text("sessionId") else null,
                acpSessionResumable = row.getBoolean("resumable"),
                historyReplaySupported = row.getBoolean("historyReplaySupported"),
                titleRevision = row.getLong("titleRevision").also { require(it >= 0) },
                titleGeneration = if (row.has("titleGeneration") && !row.isNull("titleGeneration")) row.text("titleGeneration") else null,
            )
        }
        require(chats.map { it.id }.toSet().size == chats.size) { "Duplicate chats in transfer." }
        return TransferBundle(machines, chats)
    }

    private fun validateEndpoint(value: String) {
        val uri = URI(value)
        require(uri.scheme in setOf("ws", "wss") && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.path in setOf("", "/") && (uri.port == -1 || uri.port in 1..65535)) { "Invalid Bridge endpoint." }
        if (uri.scheme == "ws") {
            val host = uri.host.lowercase()
            val octets = host.split('.').mapNotNull { it.toIntOrNull()?.takeIf { n -> n in 0..255 } }
            val privateV4 = octets.size == 4 && (octets[0] == 10 || octets[0] == 127 ||
                (octets[0] == 192 && octets[1] == 168) || (octets[0] == 172 && octets[1] in 16..31) ||
                (octets[0] == 100 && octets[1] in 64..127))
            require(privateV4 || host == "localhost" || host == "[::1]" ||
                host.endsWith(".ts.net") || host.endsWith(".local")) { "Cleartext links must use a private LAN/Tailscale endpoint." }
        }
    }

    private fun JSONObject.text(name: String, limit: Int = 256): String {
        val value = get(name)
        require(value is String && value.isNotBlank() && value.length <= limit && value.none { it.isISOControl() }) {
            "Invalid transfer field: $name."
        }
        return value
    }

    private fun encode(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun key(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, 210_000, 256)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
