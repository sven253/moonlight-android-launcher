package de.tvwol.launcher

import android.content.Context
import org.json.JSONException
import org.json.JSONObject

/**
 * Import and export of the whole configuration as JSON, so it can be prepared on a
 * computer instead of being typed in with a remote control.
 */
object ConfigIo {

    private val STRING_KEYS = listOf(
        Keys.PC_HOST, Keys.PC_PORT, Keys.PC_MAC, Keys.WAKE_TIMEOUT,
        Keys.RELAY_HOST, Keys.RELAY_PORT, Keys.RELAY_USER, Keys.RELAY_AUTH,
        Keys.RELAY_PASSWORD, Keys.RELAY_KEY, Keys.RELAY_KEY_PASSPHRASE,
        Keys.RELAY_COMMAND, Keys.HOST_KEY_FINGERPRINT,
        Keys.ML_PACKAGE, Keys.ML_CLASS, Keys.ML_PC_UUID, Keys.ML_PC_NAME,
        Keys.ML_APP_ID, Keys.ML_APP_NAME
    )

    private val BOOLEAN_KEYS = listOf(
        Keys.USE_RELAY, Keys.USE_DIRECT_WOL, Keys.HOST_KEY_CHECK
    )

    private val SECRET_KEYS = setOf(
        Keys.RELAY_PASSWORD, Keys.RELAY_KEY, Keys.RELAY_KEY_PASSPHRASE
    )

    data class ImportResult(val applied: Int, val ignored: List<String>)

    /**
     * Applies every known key found in [json]. Keys that are absent stay untouched, so a
     * partial file can be used to change only a few settings. Keys starting with an
     * underscore are treated as comments and skipped silently.
     */
    fun import(context: Context, json: String): ImportResult {
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw IllegalArgumentException(
                "Die Datei ist kein gültiges JSON: ${e.message}", e
            )
        }

        val editor = SecurePrefs.get(context).edit()
        var applied = 0
        val ignored = mutableListOf<String>()

        for (key in root.keys()) {
            when {
                key.startsWith("_") -> Unit
                key in BOOLEAN_KEYS -> {
                    editor.putBoolean(key, readBoolean(root, key))
                    applied++
                }
                key in STRING_KEYS -> {
                    editor.putString(key, root.get(key).toString().trim())
                    applied++
                }
                else -> ignored.add(key)
            }
        }

        editor.apply()
        return ImportResult(applied, ignored)
    }

    /** Accepts true/false as well as "true"/"yes"/"1". */
    private fun readBoolean(root: JSONObject, key: String): Boolean {
        val raw = root.get(key)
        if (raw is Boolean) return raw
        return raw.toString().trim().lowercase() in setOf("true", "yes", "1", "ja")
    }

    /**
     * Serialises the current settings. Secrets are left out unless explicitly requested,
     * so an exported file can be moved around without leaking the relay credentials.
     */
    fun export(context: Context, includeSecrets: Boolean): String {
        val prefs = SecurePrefs.get(context)
        val root = JSONObject()

        root.put(
            "_readme",
            "Konfiguration für Moonlight Launcher. Felder, die fehlen, bleiben " +
                "unverändert. Schlüssel mit führendem Unterstrich werden ignoriert."
        )

        for (key in STRING_KEYS) {
            if (!includeSecrets && key in SECRET_KEYS) continue
            val value = prefs.getString(key, "").orEmpty()
            if (value.isNotEmpty()) root.put(key, value)
        }
        for (key in BOOLEAN_KEYS) {
            root.put(key, prefs.getBoolean(key, key == Keys.USE_RELAY || key == Keys.HOST_KEY_CHECK))
        }

        return root.toString(2)
    }
}
