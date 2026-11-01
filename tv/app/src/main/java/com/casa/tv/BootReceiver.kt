package com.casa.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (Prefs(ctx).paired) CasaService.start(ctx)
    }
}
