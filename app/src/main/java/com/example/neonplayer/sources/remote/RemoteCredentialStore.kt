package com.example.neonplayer.sources.remote

import android.content.Context
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Senhas de servidores remotos (SMB/SFTP/FTP), criptografadas com [EncryptedSharedPreferences]
 * (chave gerenciada pelo Android Keystore) e chaveadas pelo id do [RemoteServerConfig] — o
 * protocolo não importa aqui. Nunca gravar a senha em outro lugar (DataStore, log, etc).
 */
class RemoteCredentialStore(context: Context) {

    private val preferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "remote_credentials",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun getPassword(serverId: String): String? = preferences.getString(serverId, null)

    fun savePassword(serverId: String, password: String) {
        preferences.edit { putString(serverId, password) }
    }

    fun removePassword(serverId: String) {
        preferences.edit { remove(serverId) }
    }
}
