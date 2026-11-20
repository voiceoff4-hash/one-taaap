package com.example.onetaplogin

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object CredentialStore {
    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        "vault",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun save(context: Context, packageName: String, username: String, password: String) {
        prefs(context).edit()
            .putString("$packageName.username", username)
            .putString("$packageName.password", password)
            .apply()
    }

    fun delete(context: Context, key: String) {
        prefs(context).edit()
            .remove("$key.username")
            .remove("$key.password")
            .apply()
    }

    fun get(context: Context, packageName: String): Pair<String, String>? {
        val p = prefs(context)
        val user = p.getString("$packageName.username", null) ?: return null
        val pass = p.getString("$packageName.password", null) ?: return null
        return user to pass
    }
}
