package dev.nutting.pocketllm.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Base64

private val Context.encryptedDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "encrypted_prefs",
)

class EncryptedDataStore(context: Context) {

    private val dataStore = context.encryptedDataStore
    private val aead: Aead

    init {
        AeadConfig.register()
        val keysetHandle: KeysetHandle = AndroidKeysetManager.Builder()
            .withSharedPref(context, "pocket_llm_keyset", "pocket_llm_keyset_prefs")
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://pocket_llm_master_key")
            .build()
            .keysetHandle
        aead = keysetHandle.getPrimitive(Aead::class.java)
    }

    suspend fun saveApiKey(serverId: String, apiKey: String) {
        val encrypted = aead.encrypt(apiKey.toByteArray(Charsets.UTF_8), serverId.toByteArray())
        val encoded = Base64.getEncoder().encodeToString(encrypted)
        val key = stringPreferencesKey("api_key_$serverId")
        dataStore.edit { prefs -> prefs[key] = encoded }
    }

    fun getApiKey(serverId: String): Flow<String?> {
        val key = stringPreferencesKey("api_key_$serverId")
        return dataStore.data.map { prefs ->
            prefs[key]?.let { encoded ->
                val encrypted = Base64.getDecoder().decode(encoded)
                String(aead.decrypt(encrypted, serverId.toByteArray()), Charsets.UTF_8)
            }
        }
    }

    suspend fun deleteApiKey(serverId: String) {
        val key = stringPreferencesKey("api_key_$serverId")
        dataStore.edit { prefs -> prefs.remove(key) }
    }
}
