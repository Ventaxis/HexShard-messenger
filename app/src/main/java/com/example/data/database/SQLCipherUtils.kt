package com.example.data.database

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom
import android.util.Base64
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SQLiteDatabaseHook
import net.sqlcipher.database.SupportFactory

object SQLCipherUtils {
    private const val PREFS_NAME = "hexshard_db_prefs"
    private const val DB_KEY = "db_passphrase"

    fun getPassphrase(context: Context): ByteArray {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        val sharedPreferences = EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        var passphraseBase64 = sharedPreferences.getString(DB_KEY, null)
        if (passphraseBase64 == null) {
            val random = SecureRandom()
            val passphrase = ByteArray(32)
            random.nextBytes(passphrase)
            passphraseBase64 = Base64.encodeToString(passphrase, Base64.NO_WRAP)
            sharedPreferences.edit().putString(DB_KEY, passphraseBase64).apply()
        }
        return Base64.decode(passphraseBase64, Base64.NO_WRAP)
    }

    /**
     * Creates SQLCipher SupportFactory with memory security enabled and hardened crypto pragmas.
     */
    fun createSupportFactory(context: Context): SupportFactory {
        val passphrase = getPassphrase(context)
        val hook = object : SQLiteDatabaseHook {
            override fun preKey(database: SQLiteDatabase?) {}
            override fun postKey(database: SQLiteDatabase?) {
                // Harden memory security: overwrite memory buffers when cleared
                database?.rawExecSQL("PRAGMA cipher_memory_security = ON;")
                database?.rawExecSQL("PRAGMA cipher_page_size = 4096;")
                database?.rawExecSQL("PRAGMA kdf_iter = 64000;")
            }
        }
        return SupportFactory(passphrase, hook)
    }

    /**
     * Cryptographically destroys the stored database passphrase upon account deletion.
     */
    fun clearPassphrase(context: Context) {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val sharedPreferences = EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            sharedPreferences.edit().clear().commit()
        } catch (e: Exception) {
            timber.log.Timber.w(e, "Error destroying SQLCipher passphrase")
        }
    }
}

