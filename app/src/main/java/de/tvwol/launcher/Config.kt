package de.tvwol.launcher

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * All settings live in a single [SharedPreferences] instance.
 *
 * We try to use EncryptedSharedPreferences (Keystore backed). Some cheap TV boxes ship
 * a broken or missing keystore implementation, in which case initialisation throws. We
 * then fall back to plain app-private preferences rather than refusing to start — the
 * data is still unreadable for other apps, it is just not encrypted at rest.
 */
object SecurePrefs {

    private const val TAG = "SecurePrefs"
    private const val FILE_ENCRYPTED = "settings_enc"
    private const val FILE_PLAIN = "settings"

    @Volatile
    private var instance: SharedPreferences? = null

    @Volatile
    var usingEncryption: Boolean = true
        private set

    fun get(context: Context): SharedPreferences {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            val app = context.applicationContext
            val prefs = try {
                val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
                EncryptedSharedPreferences.create(
                    FILE_ENCRYPTED,
                    masterKeyAlias,
                    app,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Encrypted preferences unavailable, falling back to plain storage", t)
                usingEncryption = false
                app.getSharedPreferences(FILE_PLAIN, Context.MODE_PRIVATE)
            }
            instance = prefs
            return prefs
        }
    }
}

object Keys {
    const val CONFIGURED = "configured"

    // Target PC
    const val PC_HOST = "pc_host"
    const val PC_PORT = "pc_port"
    const val PC_MAC = "pc_mac"
    const val WAKE_TIMEOUT = "wake_timeout"

    // Wake method
    const val USE_RELAY = "use_relay"
    const val USE_DIRECT_WOL = "use_direct_wol"

    // SSH relay
    const val RELAY_HOST = "relay_host"
    const val RELAY_PORT = "relay_port"
    const val RELAY_USER = "relay_user"
    const val RELAY_AUTH = "relay_auth" // "password" | "key"
    const val RELAY_PASSWORD = "relay_password"
    const val RELAY_KEY = "relay_key"
    const val RELAY_KEY_PASSPHRASE = "relay_key_passphrase"
    const val RELAY_COMMAND = "relay_command"
    const val HOST_KEY_FINGERPRINT = "host_key_fingerprint"
    const val HOST_KEY_CHECK = "host_key_check"

    // Moonlight
    const val ML_PACKAGE = "ml_package"
    const val ML_CLASS = "ml_class"
    const val ML_PC_UUID = "ml_pc_uuid"
    const val ML_PC_NAME = "ml_pc_name"
    const val ML_APP_ID = "ml_app_id"
    const val ML_APP_NAME = "ml_app_name"

    // Actions (no stored value)
    const val ACTION_IMPORT_KEY = "action_import_key"
    const val ACTION_TEST = "action_test"
    const val ACTION_ABOUT = "action_about"
}

data class Config(
    val configured: Boolean,
    val pcHost: String,
    val pcPort: Int,
    val pcMac: String,
    val wakeTimeoutSec: Int,
    val useRelay: Boolean,
    val useDirectWol: Boolean,
    val relayHost: String,
    val relayPort: Int,
    val relayUser: String,
    val relayAuth: String,
    val relayPassword: String,
    val relayKey: String,
    val relayKeyPassphrase: String,
    val relayCommand: String,
    val hostKeyFingerprint: String,
    val hostKeyCheck: Boolean,
    val mlPackage: String,
    val mlClass: String,
    val mlPcUuid: String,
    val mlPcName: String,
    val mlAppId: String,
    val mlAppName: String
) {
    val usesKeyAuth: Boolean get() = relayAuth == "key"

    /** The command actually sent to the relay, with %MAC% substituted. */
    fun resolvedCommand(): String = relayCommand.replace("%MAC%", pcMac.trim())

    /**
     * Minimal sanity check. Returns a human readable problem or null if everything
     * needed for a launch is present.
     */
    fun validate(): String? {
        if (pcHost.isBlank()) return "Es ist keine Adresse des Ziel-PCs eingetragen."
        if (mlPcUuid.isBlank() && mlPcName.isBlank()) {
            return "Es ist weder eine PC-UUID noch ein PC-Name für Moonlight eingetragen."
        }
        if (useRelay) {
            if (relayHost.isBlank()) return "Es ist kein Host für das WoL-Relay eingetragen."
            if (relayUser.isBlank()) return "Es ist kein Benutzername für das WoL-Relay eingetragen."
            if (usesKeyAuth && relayKey.isBlank()) return "Es ist kein privater SSH-Key hinterlegt."
            if (!usesKeyAuth && relayPassword.isBlank()) return "Es ist kein SSH-Passwort hinterlegt."
            if (resolvedCommand().isBlank()) return "Es ist kein Weck-Befehl eingetragen."
        }
        if (useDirectWol && pcMac.isBlank()) return "Für Direkt-WoL wird eine MAC-Adresse benötigt."
        if (!useRelay && !useDirectWol) return "Es ist keine Weck-Methode aktiviert."
        return null
    }

    companion object {
        const val DEFAULT_COMMAND = "/usr/local/bin/wol-relay.sh %MAC%"
        const val DEFAULT_ML_PACKAGE = "com.limelight"
        const val DEFAULT_ML_CLASS = "com.limelight.ShortcutTrampoline"

        fun load(context: Context): Config {
            val p = SecurePrefs.get(context)
            fun s(key: String, def: String = "") = p.getString(key, def)?.trim() ?: def
            fun i(key: String, def: Int) = s(key, def.toString()).toIntOrNull() ?: def
            return Config(
                configured = p.getBoolean(Keys.CONFIGURED, false),
                pcHost = s(Keys.PC_HOST),
                pcPort = i(Keys.PC_PORT, 47989),
                pcMac = s(Keys.PC_MAC),
                wakeTimeoutSec = i(Keys.WAKE_TIMEOUT, 120),
                useRelay = p.getBoolean(Keys.USE_RELAY, true),
                useDirectWol = p.getBoolean(Keys.USE_DIRECT_WOL, false),
                relayHost = s(Keys.RELAY_HOST),
                relayPort = i(Keys.RELAY_PORT, 22),
                relayUser = s(Keys.RELAY_USER),
                relayAuth = s(Keys.RELAY_AUTH, "password"),
                relayPassword = p.getString(Keys.RELAY_PASSWORD, "") ?: "",
                relayKey = p.getString(Keys.RELAY_KEY, "") ?: "",
                relayKeyPassphrase = p.getString(Keys.RELAY_KEY_PASSPHRASE, "") ?: "",
                relayCommand = s(Keys.RELAY_COMMAND, DEFAULT_COMMAND),
                hostKeyFingerprint = s(Keys.HOST_KEY_FINGERPRINT),
                hostKeyCheck = p.getBoolean(Keys.HOST_KEY_CHECK, true),
                mlPackage = s(Keys.ML_PACKAGE, DEFAULT_ML_PACKAGE),
                mlClass = s(Keys.ML_CLASS, DEFAULT_ML_CLASS),
                mlPcUuid = s(Keys.ML_PC_UUID),
                mlPcName = s(Keys.ML_PC_NAME),
                mlAppId = s(Keys.ML_APP_ID),
                mlAppName = s(Keys.ML_APP_NAME)
            )
        }

        fun markConfigured(context: Context) {
            SecurePrefs.get(context).edit().putBoolean(Keys.CONFIGURED, true).apply()
        }

        fun storeHostKeyFingerprint(context: Context, fingerprint: String) {
            SecurePrefs.get(context).edit()
                .putString(Keys.HOST_KEY_FINGERPRINT, fingerprint)
                .apply()
        }
    }
}
