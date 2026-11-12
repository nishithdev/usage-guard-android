package com.nishith.usageguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the guard after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ok = GuardPrefs(context).guardOn &&
            UsageRepo.hasUsageAccess(context) &&
            UsageRepo.canDrawOverlays(context)
        if (ok) runCatching { GuardService.start(context) }
    }
}
