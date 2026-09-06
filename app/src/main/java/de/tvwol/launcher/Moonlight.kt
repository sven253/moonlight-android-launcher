package de.tvwol.launcher

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Starts a stream through Moonlight's own shortcut entry point.
 *
 * ShortcutTrampoline is the activity Moonlight uses for its launcher shortcuts. It
 * accepts four optional string extras; one of UUID/Name identifies the PC, one of
 * AppId/AppName identifies the app. Passing an app is optional — without it Moonlight
 * just opens the app list for that PC.
 *
 * Note that Moonlight resolves Name and AppName against its own database and its cached
 * app list, so the PC has to be paired and opened at least once before name-based
 * launching works. UUID and AppId always work.
 */
object Moonlight {

    private const val EXTRA_PC_UUID = "UUID"
    private const val EXTRA_PC_NAME = "Name"
    private const val EXTRA_APP_ID = "AppId"
    private const val EXTRA_APP_NAME = "AppName"

    fun buildIntent(config: Config): Intent {
        val intent = Intent(Intent.ACTION_MAIN)
        intent.setClassName(config.mlPackage, config.mlClass)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

        // Moonlight validates the UUID strictly, so only send it when it looks like one.
        if (config.mlPcUuid.isNotBlank()) {
            intent.putExtra(EXTRA_PC_UUID, config.mlPcUuid)
        } else if (config.mlPcName.isNotBlank()) {
            intent.putExtra(EXTRA_PC_NAME, config.mlPcName)
        }

        if (config.mlAppId.isNotBlank()) {
            intent.putExtra(EXTRA_APP_ID, config.mlAppId)
        } else if (config.mlAppName.isNotBlank()) {
            intent.putExtra(EXTRA_APP_NAME, config.mlAppName)
        }

        return intent
    }

    fun launch(context: Context, config: Config) {
        try {
            context.startActivity(buildIntent(config))
        } catch (e: ActivityNotFoundException) {
            throw IllegalStateException(
                "Der Streaming-Client wurde nicht gefunden (${config.mlPackage}/${config.mlClass}). " +
                    "Ist Moonlight installiert, und stimmt der Paketname in den Einstellungen?",
                e
            )
        } catch (e: SecurityException) {
            throw IllegalStateException(
                "Der Streaming-Client hat den Start abgelehnt. Diese Moonlight-Version " +
                    "erlaubt möglicherweise keine externen Verknüpfungen.",
                e
            )
        }
    }

    fun isInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
