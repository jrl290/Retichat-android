package com.newendian.retichat.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.newendian.retichat.service.WakeWorker

class SimulateFcmWakeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        Log.i(TAG, "Debug FCM wake broadcast received")
        WakeWorker.enqueue(context.applicationContext)
    }

    companion object {
        const val ACTION = "com.newendian.retichat.DEBUG_SIMULATE_FCM_WAKE"
        private const val TAG = "SimFcmWake"
    }
}