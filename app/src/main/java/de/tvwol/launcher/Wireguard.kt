package de.tvwol.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

/**
 * Brings a tunnel of the official WireGuard app up, using its broadcast API.
 *
 * WireGuard exports a receiver that accepts SET_TUNNEL_UP / SET_TUNNEL_DOWN with the
 * tunnel name as a string extra. Three things have to line up for this to work:
 *
 *  1. the receiver is protected by CONTROL_TUNNELS, a dangerous permission declared by
 *     WireGuard itself, so it has to be requested at runtime;
 *  2. "Allow remote control apps" has to be enabled in the WireGuard settings —
 *     without it the broadcast is dropped silently;
 *  3. the VPN consent dialog (VpnService.prepare) must have been accepted once, which
 *     happens automatically the first time the tunnel is started inside WireGuard.
 *
 * There is no result or callback of any kind, so success is detected by polling the
 * connectivity manager for a VPN transport.
 */
object Wireguard {

    private const val TAG = "Wireguard"

    /** Declared by the WireGuard app, protectionLevel="dangerous". */
    const val PERMISSION = "com.wireguard.android.permission.CONTROL_TUNNELS"

    const val DEFAULT_PACKAGE = "com.wireguard.android"

    private const val RECEIVER_CLASS = "com.wireguard.android.model.TunnelManager\$IntentReceiver"
    private const val ACTION_UP = "com.wireguard.android.action.SET_TUNNEL_UP"
    private const val ACTION_DOWN = "com.wireguard.android.action.SET_TUNNEL_DOWN"
    private const val EXTRA_TUNNEL = "tunnel"

    private const val POLL_INTERVAL_MS = 500L

    fun isInstalled(context: Context, pkg: String): Boolean =
        try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * True as soon as any VPN interface is up. We cannot ask WireGuard which tunnel is
     * active — the API is fire and forget — but on a TV box that only has this one VPN
     * configured, a VPN transport is a reliable enough signal.
     */
    @Suppress("DEPRECATION")
    fun isUp(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        return cm.allNetworks.any { network ->
            cm.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    fun setTunnel(context: Context, pkg: String, tunnel: String, up: Boolean) {
        val intent = Intent(if (up) ACTION_UP else ACTION_DOWN)
        intent.component = ComponentName(pkg, RECEIVER_CLASS)
        intent.putExtra(EXTRA_TUNNEL, tunnel)
        Log.i(TAG, "Broadcast ${intent.action} for tunnel $tunnel to $pkg")
        try {
            context.sendBroadcast(intent)
        } catch (e: SecurityException) {
            throw IllegalStateException(
                "WireGuard hat den Befehl abgelehnt. Vermutlich fehlt die Berechtigung " +
                    "CONTROL_TUNNELS.",
                e
            )
        }
    }

    /** Polls until a VPN interface shows up. Returns false on timeout. */
    suspend fun waitUntilUp(context: Context, timeoutMs: Long): Boolean {
        val start = SystemClock.elapsedRealtime()
        while (true) {
            if (isUp(context)) return true
            if (SystemClock.elapsedRealtime() - start >= timeoutMs) return false
            delay(POLL_INTERVAL_MS)
        }
    }
}
