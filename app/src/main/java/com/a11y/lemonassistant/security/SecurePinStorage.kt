package com.a11y.lemonassistant.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecurePinStorage(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "lemon_a11y_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun savePin(pin: String) {
        sharedPreferences.edit()
            .putString(KEY_LOGIN_PIN, pin)
            .apply()
    }

    fun getPin(): String? {
        return sharedPreferences.getString(KEY_LOGIN_PIN, null)
    }

    fun hasPin(): Boolean {
        return !getPin().isNullOrBlank()
    }

    fun clearPin() {
        sharedPreferences.edit()
            .remove(KEY_LOGIN_PIN)
            .apply()
    }

    companion object {
        private const val KEY_LOGIN_PIN = "lemon_login_pin"
    }
}
