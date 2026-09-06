package de.tvwol.launcher

import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    override fun onStop() {
        super.onStop()
        // Once the configuration is complete, stop showing the settings on every start.
        if (Config.load(this).validate() == null) {
            Config.markConfigured(this)
        }
    }
}

/** Routes androidx.preference through our (encrypted) SharedPreferences instance. */
private class SecurePreferenceDataStore(private val prefs: SharedPreferences) : PreferenceDataStore() {
    override fun putString(key: String, value: String?) {
        prefs.edit().putString(key, value).apply()
    }

    override fun getString(key: String, defValue: String?): String? = prefs.getString(key, defValue)

    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    override fun getBoolean(key: String, defValue: Boolean): Boolean = prefs.getBoolean(key, defValue)
}

class SettingsFragment : PreferenceFragmentCompat() {

    private val importKeyLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) importPrivateKey(uri)
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore =
            SecurePreferenceDataStore(SecurePrefs.get(requireContext()))
        setPreferencesFromResource(R.xml.settings, rootKey)

        numeric(Keys.PC_PORT)
        numeric(Keys.RELAY_PORT)
        numeric(Keys.WAKE_TIMEOUT)
        numeric(Keys.ML_APP_ID)

        secret(Keys.RELAY_PASSWORD)
        secret(Keys.RELAY_KEY_PASSPHRASE)
        multiline(Keys.RELAY_KEY)

        findPreference<Preference>(Keys.ACTION_IMPORT_KEY)?.setOnPreferenceClickListener {
            importKeyLauncher.launch(arrayOf("*/*"))
            true
        }

        findPreference<Preference>(Keys.ACTION_TEST)?.setOnPreferenceClickListener {
            runTest()
            true
        }

        findPreference<Preference>(Keys.HOST_KEY_FINGERPRINT)?.let { pref ->
            pref.setOnPreferenceClickListener {
                SecurePrefs.get(requireContext()).edit()
                    .remove(Keys.HOST_KEY_FINGERPRINT).apply()
                updateFingerprintSummary()
                true
            }
        }

        findPreference<Preference>(Keys.ACTION_ABOUT)?.summary = buildString {
            append("Version ${BuildConfig.VERSION_NAME}. ")
            append(
                if (SecurePrefs.usingEncryption) {
                    "Zugangsdaten liegen verschlüsselt im App-Speicher."
                } else {
                    "Achtung: Verschlüsselter Speicher ist auf diesem Gerät nicht verfügbar, " +
                        "Zugangsdaten liegen im Klartext im App-Speicher."
                }
            )
        }

        updateFingerprintSummary()
        updateAuthVisibility()

        findPreference<ListPreference>(Keys.RELAY_AUTH)?.setOnPreferenceChangeListener { _, value ->
            view?.post { updateAuthVisibility(value as? String) }
            true
        }
        findPreference<SwitchPreferenceCompat>(Keys.USE_RELAY)?.setOnPreferenceChangeListener { _, _ ->
            view?.post { updateAuthVisibility() }
            true
        }
    }

    private fun numeric(key: String) {
        findPreference<EditTextPreference>(key)?.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_NUMBER
            editText.setSelection(editText.text.length)
        }
    }

    private fun multiline(key: String) {
        findPreference<EditTextPreference>(key)?.let { pref ->
            pref.setOnBindEditTextListener { editText ->
                editText.inputType =
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                editText.setSingleLine(false)
                editText.maxLines = 8
            }
            pref.summaryProvider = Preference.SummaryProvider<EditTextPreference> {
                val text = it.text.orEmpty()
                when {
                    text.isBlank() -> "Kein Key hinterlegt"
                    else -> "Key hinterlegt (${text.length} Zeichen)"
                }
            }
        }
    }

    private fun secret(key: String) {
        findPreference<EditTextPreference>(key)?.let { pref ->
            pref.setOnBindEditTextListener { editText ->
                editText.inputType =
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            pref.summaryProvider = Preference.SummaryProvider<EditTextPreference> {
                if (it.text.isNullOrEmpty()) "Nicht gesetzt" else "••••••••"
            }
        }
    }

    private fun updateFingerprintSummary() {
        val stored = SecurePrefs.get(requireContext())
            .getString(Keys.HOST_KEY_FINGERPRINT, "")
            .orEmpty()
        findPreference<Preference>(Keys.HOST_KEY_FINGERPRINT)?.summary =
            if (stored.isBlank()) {
                "Noch kein Fingerprint gespeichert. Er wird bei der ersten Verbindung übernommen."
            } else {
                "$stored\nZum Zurücksetzen auswählen."
            }
    }

    private fun updateAuthVisibility(authOverride: String? = null) {
        val config = Config.load(requireContext())
        val auth = authOverride ?: config.relayAuth
        val relayOn = config.useRelay

        findPreference<Preference>(Keys.RELAY_PASSWORD)?.isVisible = relayOn && auth != "key"
        findPreference<Preference>(Keys.RELAY_KEY)?.isVisible = relayOn && auth == "key"
        findPreference<Preference>(Keys.RELAY_KEY_PASSPHRASE)?.isVisible = relayOn && auth == "key"
        findPreference<Preference>(Keys.ACTION_IMPORT_KEY)?.isVisible = relayOn && auth == "key"
    }

    private fun importPrivateKey(uri: Uri) {
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openInputStream(uri)?.use { stream ->
                        stream.readBytes().toString(Charsets.UTF_8)
                    } ?: error("Datei konnte nicht geöffnet werden.")
                }
            }
            result.onSuccess { content ->
                if (!content.contains("PRIVATE KEY")) {
                    dialog(
                        "Import fehlgeschlagen",
                        "Die Datei sieht nicht nach einem privaten SSH-Key aus. " +
                            "Erwartet wird eine Datei, die mit -----BEGIN … PRIVATE KEY----- beginnt."
                    )
                    return@onSuccess
                }
                SecurePrefs.get(requireContext()).edit()
                    .putString(Keys.RELAY_KEY, content).apply()
                findPreference<EditTextPreference>(Keys.RELAY_KEY)?.text = content
                dialog("Key importiert", "Der private Key wurde übernommen.")
            }.onFailure {
                dialog("Import fehlgeschlagen", it.message ?: it.toString())
            }
        }
    }

    private fun runTest() {
        val config = Config.load(requireContext())
        config.validate()?.let {
            dialog("Einstellungen unvollständig", it)
            return
        }

        val progress = AlertDialog.Builder(requireContext())
            .setTitle("Test läuft")
            .setMessage("Verbindung wird geprüft …")
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            val report = StringBuilder()

            val clientInstalled = Moonlight.isInstalled(requireContext(), config.mlPackage)
            report.append(
                if (clientInstalled) "Client ${config.mlPackage}: installiert\n"
                else "Client ${config.mlPackage}: NICHT gefunden\n"
            )

            val up = Net.isHostUp(config.pcHost, config.pcPort, 2000)
            report.append(
                if (up) "PC ${config.pcHost}:${config.pcPort}: erreichbar\n"
                else "PC ${config.pcHost}:${config.pcPort}: nicht erreichbar\n"
            )

            if (config.useRelay) {
                runCatching { SshRelay.exec(config, config.resolvedCommand()) }
                    .onSuccess { result ->
                        result.hostKeyFingerprint?.let { fingerprint ->
                            if (config.hostKeyFingerprint.isBlank()) {
                                Config.storeHostKeyFingerprint(requireContext(), fingerprint)
                            }
                            report.append("Hostkey: $fingerprint\n")
                        }
                        report.append("Relay-Befehl: Exit-Code ${result.exitCode}\n")
                        if (result.stdout.isNotBlank()) report.append("Ausgabe: ${result.stdout}\n")
                        if (result.stderr.isNotBlank()) report.append("Fehlerausgabe: ${result.stderr}\n")
                    }
                    .onFailure { report.append("Relay: ${it.message}\n") }
            }

            progress.dismiss()
            updateFingerprintSummary()
            dialog("Testergebnis", report.toString().trim())
        }
    }

    private fun dialog(title: String, message: String) {
        if (!isAdded) return
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Give the list a sane amount of overscan padding on TVs.
        listView.setPadding(48, 24, 48, 48)
        listView.clipToPadding = false
    }
}
