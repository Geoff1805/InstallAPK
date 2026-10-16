package fr.installapk.app.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import fr.installapk.app.R

/**
 * Gestionnaire d'alertes et notifications hiérarchisées (Composant référent STACK_LIBRARY.md § 3.1).
 * Réutilisé depuis AppliHome / Bullix avec 3 canaux typés :
 * - CRITICAL_ALERTS : Token GitHub expiré, conflit de signature APK.
 * - ROUTINES_SCENARIOS : Mises à jour automatiques au lancement et fin de file d'attente.
 * - INFO : Téléchargements et informations générales.
 */
object NotificationHelper {

    const val CHANNEL_CRITICAL = "CRITICAL_ALERTS"
    const val CHANNEL_UPDATES = "ROUTINES_SCENARIOS"
    const val CHANNEL_INFO = "INFO"

    fun initChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            val criticalChannel = NotificationChannel(
                CHANNEL_CRITICAL,
                "Alertes Critiques (Clé GitHub & Sécurité)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alertes immédiates en cas de clé GitHub expirée ou conflit de signature"
                enableVibration(true)
            }

            val updatesChannel = NotificationChannel(
                CHANNEL_UPDATES,
                "Mises à jour Automatiques",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Suivi de la file de mise à jour automatique des applications"
            }

            val infoChannel = NotificationChannel(
                CHANNEL_INFO,
                "Informations & Synchronisation",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications silencieuses de synchronisation GitHub"
            }

            manager.createNotificationChannels(listOf(criticalChannel, updatesChannel, infoChannel))
        }
    }

    fun notifyCritical(context: Context, id: Int, title: String, message: String) {
        postNotification(context, CHANNEL_CRITICAL, id, title, message, NotificationCompat.PRIORITY_HIGH)
    }

    fun notifyUpdateProgress(context: Context, id: Int, title: String, message: String) {
        postNotification(context, CHANNEL_UPDATES, id, title, message, NotificationCompat.PRIORITY_DEFAULT)
    }

    fun notifyInfo(context: Context, id: Int, title: String, message: String) {
        postNotification(context, CHANNEL_INFO, id, title, message, NotificationCompat.PRIORITY_LOW)
    }

    private fun postNotification(
        context: Context,
        channelId: String,
        notificationId: Int,
        title: String,
        message: String,
        priority: Int
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        try {
            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(priority)
                .setAutoCancel(true)
                .build()

            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (_: Exception) {
        }
    }
}
