package de.tvwol.launcher

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The whole app, essentially: figure out whether the PC is awake, wake it if not,
 * then hand over to Moonlight and get out of the way.
 */
class LaunchActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "LaunchActivity"
        const val PROBE_TIMEOUT_MS = 1200
        const val POLL_INTERVAL_MS = 1500L

        // The VPN interface appears a moment before the handshake has actually
        // completed. Probing right away would fail for no good reason.
        const val TUNNEL_SETTLE_MS = 1000L

        // After the tunnel came up, keep probing for a while instead of deciding after a
        // single attempt. A handshake plus the first routed packets can easily take a few
        // seconds, and a premature "not reachable" would send us into the wake path — and
        // from there into an SSH connect that fails hard because the route is not up yet.
        const val TUNNEL_GRACE_MS = 8000L
    }

    private lateinit var statusView: TextView
    private lateinit var detailView: TextView
    private lateinit var progressView: ProgressBar
    private lateinit var retryButton: Button
    private lateinit var settingsButton: Button

    private var job: Job? = null
    private var launched = false
    private var awaitingSettings = false
    private var awaitingPermission = false
    private var permissionAsked = false

    private val settingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            awaitingSettings = false
            startFlow()
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            awaitingPermission = false
            if (granted) {
                startFlow()
            } else {
                showError(getString(R.string.error_wg_permission_title), permissionProblem())
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launch)

        statusView = findViewById(R.id.status)
        detailView = findViewById(R.id.detail)
        progressView = findViewById(R.id.progress)
        retryButton = findViewById(R.id.retry)
        settingsButton = findViewById(R.id.settings)

        retryButton.setOnClickListener { startFlow() }
        settingsButton.setOnClickListener { openSettings() }
    }

    override fun onStart() {
        super.onStart()
        if (!launched && !awaitingSettings && !awaitingPermission && job == null) {
            startFlow()
        }
    }

    override fun onStop() {
        super.onStop()
        job?.cancel()
        job = null
    }

    private fun openSettings() {
        awaitingSettings = true
        job?.cancel()
        job = null
        settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
    }

    private fun startFlow() {
        job?.cancel()
        showBusy()

        val config = Config.load(this)

        if (!config.configured) {
            statusView.text = getString(R.string.first_run)
            openSettings()
            return
        }

        config.validate()?.let { problem ->
            showError(getString(R.string.error_config), problem)
            return
        }

        // Asking for CONTROL_TUNNELS needs an activity, so it happens here rather than
        // inside the flow. Only once per activity instance: if the user says no, we show
        // the error instead of looping on the dialog.
        if (config.usesWireguard &&
            !permissionAsked &&
            !Wireguard.hasPermission(this) &&
            Wireguard.isInstalled(this, config.wgPackage)
        ) {
            permissionAsked = true
            awaitingPermission = true
            permissionLauncher.launch(Wireguard.PERMISSION)
            return
        }

        job = lifecycleScope.launch {
            try {
                runFlow(config)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Launch failed", e)
                showError(getString(R.string.error_generic), e.message ?: e.toString())
            }
        }
    }

    private suspend fun runFlow(config: Config) {
        var tunnelJustStarted = false
        if (config.wgMode == Config.WG_MODE_ALWAYS) {
            tunnelJustStarted = ensureTunnel(config)
        }

        setStatus(getString(R.string.status_probing, config.pcHost, config.pcPort))
        var reachable = if (tunnelJustStarted) {
            setStatus(getString(R.string.status_wg_probe))
            probeWithGrace(config, TUNNEL_GRACE_MS)
        } else {
            Net.isHostUp(config.pcHost, config.pcPort, PROBE_TIMEOUT_MS)
        }

        // In auto mode the tunnel is only worth the wait when the PC cannot be reached
        // directly — at home that keeps the launch as fast as it was before.
        if (!reachable && config.wgMode == Config.WG_MODE_AUTO) {
            if (ensureTunnel(config)) {
                setStatus(getString(R.string.status_wg_probe))
                reachable = probeWithGrace(config, TUNNEL_GRACE_MS)
            }
        }

        if (!reachable) {
            wake(config)
            waitForHost(config)
        }

        setStatus(getString(R.string.status_starting))
        Moonlight.launch(this, config)
        launched = true
        finish()
    }

    /**
     * Makes sure a tunnel is up before anything else touches the network. Relevant for
     * the relay too: if the relay only lives behind the VPN, the SSH step would fail
     * without this.
     */
    private suspend fun ensureTunnel(config: Config): Boolean {
        if (Wireguard.isUp(this)) return false

        if (!Wireguard.isInstalled(this, config.wgPackage)) {
            throw IllegalStateException(getString(R.string.error_wg_missing, config.wgPackage))
        }
        if (!Wireguard.hasPermission(this)) {
            throw IllegalStateException(permissionProblem())
        }

        setStatus(getString(R.string.status_wg, config.wgTunnel))
        Wireguard.setTunnel(this, config.wgPackage, config.wgTunnel, up = true)

        if (!Wireguard.waitUntilUp(this, config.wgTimeoutSec * 1000L)) {
            throw IllegalStateException(
                getString(R.string.error_wg_timeout, config.wgTimeoutSec, config.wgTunnel)
            )
        }
        delay(TUNNEL_SETTLE_MS)
        return true
    }

    /** Distinguishes "user said no" from "the system does not know the permission". */
    private fun permissionProblem(): String =
        if (Wireguard.isPermissionKnown(this)) {
            getString(R.string.error_wg_permission)
        } else {
            getString(R.string.error_wg_permission_unknown)
        }

    /** Probes repeatedly for up to [graceMs], for use right after the tunnel came up. */
    private suspend fun probeWithGrace(config: Config, graceMs: Long): Boolean {
        val start = SystemClock.elapsedRealtime()
        while (true) {
            if (Net.isHostUp(config.pcHost, config.pcPort, PROBE_TIMEOUT_MS)) return true
            if (SystemClock.elapsedRealtime() - start >= graceMs) return false
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun wake(config: Config) {
        val mac = config.normalizedMac
        if (config.useDirectWol && mac != null) {
            setStatus(getString(R.string.status_magic_packet))
            try {
                Net.sendMagicPacket(mac)
            } catch (e: Exception) {
                // Not fatal: the relay is the primary path, direct WoL is a bonus.
                Log.w(TAG, "Direct WoL failed", e)
                setDetail(getString(R.string.detail_direct_wol_failed, e.message ?: ""))
            }
        }

        if (!config.useRelay) return

        setStatus(getString(R.string.status_relay, config.relayHost))
        val result = SshRelay.exec(config, config.resolvedCommand())

        // Trust on first use: remember the fingerprint we saw the first time.
        result.hostKeyFingerprint?.let { fingerprint ->
            if (config.hostKeyFingerprint.isBlank()) {
                Config.storeHostKeyFingerprint(this, fingerprint)
            }
        }

        if (result.exitCode != 0) {
            val detail = listOf(result.stderr, result.stdout)
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
            throw IllegalStateException(
                getString(R.string.error_relay_exit, result.exitCode, detail)
            )
        }
    }

    private suspend fun waitForHost(config: Config) {
        val start = SystemClock.elapsedRealtime()
        val timeoutMs = config.wakeTimeoutSec * 1000L

        while (true) {
            val elapsed = SystemClock.elapsedRealtime() - start
            setStatus(getString(R.string.status_waiting, elapsed / 1000, config.wakeTimeoutSec))

            if (Net.isHostUp(config.pcHost, config.pcPort, PROBE_TIMEOUT_MS)) return

            if (SystemClock.elapsedRealtime() - start >= timeoutMs) {
                throw IllegalStateException(
                    getString(R.string.error_timeout, config.wakeTimeoutSec, config.pcHost, config.pcPort)
                )
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private fun setStatus(text: String) {
        statusView.text = text
    }

    private fun setDetail(text: String) {
        detailView.text = text
        detailView.visibility = View.VISIBLE
    }

    private fun showBusy() {
        progressView.visibility = View.VISIBLE
        detailView.visibility = View.GONE
        retryButton.visibility = View.GONE
        settingsButton.visibility = View.GONE
        statusView.text = getString(R.string.status_starting_up)
    }

    private fun showError(title: String, detail: String) {
        progressView.visibility = View.GONE
        statusView.text = title
        setDetail(detail)
        retryButton.visibility = View.VISIBLE
        settingsButton.visibility = View.VISIBLE
        retryButton.requestFocus()
    }
}
