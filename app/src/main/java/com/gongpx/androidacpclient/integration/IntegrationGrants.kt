package com.gongpx.androidacpclient.integration

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

internal data class CallerIdentity(val packageName: String, val uid: Int, val label: String, val signers: Set<String>)
internal data class IntegrationGrant(
    val packageName: String, val signers: Set<String>, val machineIds: Set<String>,
    val permissions: Set<String>, val grantedAt: Long,
)

internal fun IntegrationGrant.matches(identity: CallerIdentity): Boolean =
    packageName == identity.packageName && signers.isNotEmpty() && signers == identity.signers

internal fun callerIdentity(context: Context, packageName: String, expectedUid: Int? = null): CallerIdentity {
    val pm = context.packageManager
    @Suppress("DEPRECATION")
    val info = pm.getPackageInfo(packageName,
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
    val appInfo = info.applicationInfo ?: throw SecurityException("Caller has no application identity.")
    val uid = appInfo.uid
    if (expectedUid != null && expectedUid != uid) throw SecurityException("Caller UID mismatch.")
    // Binder cannot distinguish two packages sharing a UID. Never transfer a package grant to its UID siblings.
    if (pm.getPackagesForUid(uid)?.toSet() != setOf(packageName)) throw SecurityException("Shared UID callers are not supported.")
    @Suppress("DEPRECATION")
    val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
    val digests = signatures.orEmpty().map { signature ->
        MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
    }.toSet()
    if (digests.isEmpty()) throw SecurityException("Caller signer unavailable.")
    return CallerIdentity(packageName, uid, pm.getApplicationLabel(appInfo).toString(), digests)
}

internal fun identityForUid(context: Context, uid: Int): CallerIdentity {
    if (uid < 0) throw SecurityException("Missing Binder caller UID.")
    val name = context.packageManager.getPackagesForUid(uid)?.singleOrNull()
        ?: throw SecurityException("Caller identity is ambiguous.")
    return callerIdentity(context, name, uid)
}

internal class IntegrationGrants(context: Context) {
    private val preferences = EncryptedSharedPreferences.create(
        context.applicationContext, "integration_grants",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun all(): List<IntegrationGrant> = synchronized(lock) {
        preferences.all.values.mapNotNull { value ->
            (value as? String)?.let { raw ->
                runCatching {
                    val json = JSONObject(raw)
                    IntegrationGrant(json.getString("packageName"), json.getJSONArray("signers").strings(),
                        json.getJSONArray("machineIds").strings(), json.getJSONArray("permissions").strings(),
                        json.getLong("grantedAt"))
                }.getOrNull()
            }
        }
    }

    fun find(identity: CallerIdentity): IntegrationGrant? =
        all().firstOrNull { it.matches(identity) }

    fun save(identity: CallerIdentity, machineIds: Set<String>, permissions: Set<String>) = synchronized(lock) {
        require(machineIds.isNotEmpty() && permissions.isNotEmpty())
        require(permissions.all { it in setOf("read", "control", "create") })
        val json = JSONObject().put("packageName", identity.packageName).put("signers", JSONArray(identity.signers.toList()))
            .put("machineIds", JSONArray(machineIds.toList())).put("permissions", JSONArray(permissions.toList()))
            .put("grantedAt", System.currentTimeMillis())
        check(preferences.edit().putString(identity.packageName, json.toString()).commit()) { "Could not save authorization." }
    }

    fun revoke(packageName: String) = synchronized(lock) {
        check(preferences.edit().remove(packageName).commit()) { "Could not revoke authorization." }
    }

    private fun JSONArray.strings(): Set<String> = (0 until length()).map { getString(it) }.toSet()
    companion object { private val lock = Any() }
}
