package dev.ryuya.securefolderbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Credential-encrypted preferences are read only after the main user unlocks. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!settings.getBoolean("auto_boot", true)) return
        try {
            context.startForegroundService(
                Intent(context, BridgeService::class.java).setAction(BridgeService.AUTO_START),
            )
        } catch (error: Exception) {
            settings
                .edit()
                .putString("state", "ERROR: ${error.message ?: error.javaClass.simpleName}")
                .apply()
            context.sendBroadcast(
                Intent(BridgeService.ACTION_STATE).setPackage(context.packageName),
            )
        }
    }
}
