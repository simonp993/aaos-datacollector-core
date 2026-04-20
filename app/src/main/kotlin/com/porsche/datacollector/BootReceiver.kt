package com.porsche.datacollector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.porsche.sportapps.core.logging.Logger
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var logger: Logger

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            logger.i(TAG, "Boot completed — starting DataCollectorService")
            DataCollectorService.start(context)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
