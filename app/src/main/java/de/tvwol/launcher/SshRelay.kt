package de.tvwol.launcher

import android.util.Base64
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class SshException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class SshResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    /** Fingerprint of the host key we talked to, for trust-on-first-use storage. */
    val hostKeyFingerprint: String?
)

object SshRelay {

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val COMMAND_TIMEOUT_MS = 30_000

    suspend fun exec(config: Config, command: String): SshResult = withContext(Dispatchers.IO) {
        val jsch = JSch()

        if (config.usesKeyAuth) {
            try {
                jsch.addIdentity(
                    "relay",
                    config.relayKey.toByteArray(Charsets.UTF_8),
                    null,
                    config.relayKeyPassphrase.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
                )
            } catch (e: JSchException) {
                throw SshException(
                    "Der private SSH-Key konnte nicht gelesen werden. " +
                        "Stimmt das Format, und ist die Passphrase korrekt?",
                    e
                )
            }
        }

        val repository = PinnedHostKeyRepository(
            expected = config.hostKeyFingerprint.takeIf { it.isNotBlank() && config.hostKeyCheck },
            enforcing = config.hostKeyCheck
        )
        jsch.setHostKeyRepository(repository)

        val session: Session = try {
            jsch.getSession(config.relayUser, config.relayHost, config.relayPort)
        } catch (e: JSchException) {
            throw SshException("Die SSH-Sitzung konnte nicht aufgebaut werden: ${e.message}", e)
        }

        session.setConfig("StrictHostKeyChecking", "yes")
        if (config.usesKeyAuth) {
            session.setConfig("PreferredAuthentications", "publickey")
        } else {
            session.setConfig("PreferredAuthentications", "password,keyboard-interactive")
            session.setPassword(config.relayPassword)
        }
        session.setUserInfo(SilentUserInfo(config.relayPassword, config.relayKeyPassphrase))
        session.setTimeout(COMMAND_TIMEOUT_MS)

        try {
            session.connect(CONNECT_TIMEOUT_MS)
        } catch (e: JSchException) {
            val message = e.message.orEmpty()
            throw when {
                repository.mismatch -> SshException(
                    "Der Hostkey des Relays hat sich geändert. Erwartet wurde " +
                        "${config.hostKeyFingerprint}, gemeldet wurde ${repository.seen}. " +
                        "Wenn das Relay neu aufgesetzt wurde, lösche den gespeicherten " +
                        "Fingerprint in den Einstellungen.",
                    e
                )
                message.contains("Auth fail", true) || message.contains("auth cancel", true) ->
                    SshException("Die Anmeldung am Relay wurde abgelehnt. Benutzername, Passwort oder Key prüfen.", e)
                message.contains("UnknownHost", true) ->
                    SshException("Der Relay-Host ${config.relayHost} ist nicht auflösbar.", e)
                else -> SshException("Verbindung zum Relay fehlgeschlagen: $message", e)
            }
        } catch (e: Exception) {
            throw SshException("Verbindung zum Relay fehlgeschlagen: ${e.message}", e)
        }

        try {
            val channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            val stderrBuffer = ByteArrayOutputStream()
            channel.setErrStream(stderrBuffer)
            val stdoutStream = channel.inputStream
            val stdoutBuffer = ByteArrayOutputStream()

            channel.connect(CONNECT_TIMEOUT_MS)

            val buffer = ByteArray(4096)
            val deadline = System.currentTimeMillis() + COMMAND_TIMEOUT_MS
            while (true) {
                while (stdoutStream.available() > 0) {
                    val read = stdoutStream.read(buffer)
                    if (read < 0) break
                    stdoutBuffer.write(buffer, 0, read)
                }
                if (channel.isClosed) break
                if (System.currentTimeMillis() > deadline) {
                    throw SshException("Der Weck-Befehl auf dem Relay hat nicht rechtzeitig geantwortet.")
                }
                Thread.sleep(100)
            }

            SshResult(
                exitCode = channel.exitStatus,
                stdout = stdoutBuffer.toString("UTF-8").trim(),
                stderr = stderrBuffer.toString("UTF-8").trim(),
                hostKeyFingerprint = repository.seen
            ).also { channel.disconnect() }
        } catch (e: SshException) {
            throw e
        } catch (e: Exception) {
            throw SshException("Der Befehl auf dem Relay ist fehlgeschlagen: ${e.message}", e)
        } finally {
            session.disconnect()
        }
    }

    /** OpenSSH style fingerprint, e.g. SHA256:abc… — identical to `ssh-keygen -lf`. */
    fun fingerprintOf(keyBlob: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(keyBlob)
        val encoded = Base64.encodeToString(digest, Base64.NO_PADDING or Base64.NO_WRAP)
        return "SHA256:$encoded"
    }

    /**
     * Trust on first use. The check runs during key exchange, i.e. *before* any
     * credentials are sent, so a swapped host never gets to see the password.
     */
    private class PinnedHostKeyRepository(
        private val expected: String?,
        private val enforcing: Boolean
    ) : HostKeyRepository {

        var seen: String? = null
            private set
        var mismatch: Boolean = false
            private set

        override fun check(host: String?, key: ByteArray): Int {
            val fingerprint = fingerprintOf(key)
            seen = fingerprint
            return when {
                !enforcing -> HostKeyRepository.OK
                expected.isNullOrBlank() -> HostKeyRepository.OK // first contact, will be stored
                expected == fingerprint -> HostKeyRepository.OK
                else -> {
                    mismatch = true
                    HostKeyRepository.CHANGED
                }
            }
        }

        override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID(): String = "pinned"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    /**
     * Never prompts; there is no keyboard in front of this app. Also answers
     * keyboard-interactive challenges, because a fair number of sshd setups offer
     * that instead of plain password auth.
     */
    private class SilentUserInfo(
        private val password: String,
        private val passphrase: String
    ) : UserInfo, UIKeyboardInteractive {
        override fun getPassphrase(): String = passphrase
        override fun getPassword(): String = password
        override fun promptPassword(message: String?): Boolean = password.isNotEmpty()
        override fun promptPassphrase(message: String?): Boolean = passphrase.isNotEmpty()
        override fun promptYesNo(message: String?): Boolean = false
        override fun showMessage(message: String?) = Unit

        override fun promptKeyboardInteractive(
            destination: String?,
            name: String?,
            instruction: String?,
            prompt: Array<out String>?,
            echo: BooleanArray?
        ): Array<String>? {
            if (password.isEmpty() || prompt == null) return null
            // Answer every non-echoing prompt with the password; anything else we cannot know.
            return Array(prompt.size) { index ->
                if (echo != null && index < echo.size && echo[index]) "" else password
            }
        }
    }
}
