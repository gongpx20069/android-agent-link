package com.gongpx.androidacpclient.data.tunnel

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

internal interface AccountPersistence {
    fun load(provider: LoginProvider): AccountTokens?
    fun save(tokens: AccountTokens)
    fun remove(provider: LoginProvider)
}

class AccountStore(context: Context) : AccountPersistence {
    private val preferences = EncryptedSharedPreferences.create(
        context, "tunnel_accounts",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun load(provider: LoginProvider): AccountTokens? {
        val json = preferences.getString(provider.name, null)?.let(::JSONObject) ?: return null
        return AccountTokens(
            LoginAccount(provider, json.getString("id"), json.getString("label")),
            json.getString("accessToken"), json.optionalText("refreshToken"),
            if (json.has("expiresAt")) json.getLong("expiresAt") else null,
        )
    }

    override fun save(tokens: AccountTokens) {
        val json = JSONObject().put("id", tokens.account.id).put("label", tokens.account.label)
            .put("accessToken", tokens.accessToken).put("refreshToken", tokens.refreshToken)
            .put("expiresAt", tokens.expiresAt)
        check(preferences.edit().putString(tokens.account.provider.name, json.toString()).commit()) { "Could not save account credentials." }
    }

    override fun remove(provider: LoginProvider) {
        check(preferences.edit().remove(provider.name).commit()) { "Could not remove account credentials." }
    }
}
