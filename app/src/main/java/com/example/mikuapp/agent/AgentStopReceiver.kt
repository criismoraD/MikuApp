package com.example.mikuapp.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Parada de emergencia desde la notificación persistente, incluso cuando el
 * usuario está en otra aplicación.
 */
class AgentStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == ACTION_STOP) {
            ArtemisAgentLoop.stop("Botón de emergencia de la notificación")
        }
    }

    companion object {
        const val ACTION_STOP = "com.example.mikuapp.agent.STOP"
    }
}
