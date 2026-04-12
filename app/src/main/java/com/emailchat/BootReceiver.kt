package com.emailchat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.emailchat.service.EmailSyncService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            EmailSyncService.start(context)
        }
    }
}