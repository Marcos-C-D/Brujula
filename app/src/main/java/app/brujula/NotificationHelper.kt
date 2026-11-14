package app.brujula

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** Avisos del sistema. Si el usuario no ha dado permiso, no se envía nada (y la app funciona igual). */
object NotificationHelper {
    private const val CHANNEL = "brujula-eventos"
    private const val PREFS = "brujula-notificaciones"

    fun areEnabled(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    fun notify(context: Context, key: String, title: String, text: String) {
        try {
            if (!areEnabled(context)) return
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Novedades de Brújula", NotificationManager.IMPORTANCE_DEFAULT))
            }
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(key.hashCode(), n)
        } catch (e: Exception) {
            // un aviso fallido nunca debe romper el procesado
        }
    }

    /** true la primera vez que se pregunta por una clave (para avisar una sola vez de cada fecha de impuestos). */
    fun once(context: Context, key: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getStringSet("avisados", emptySet()) ?: emptySet()
        if (key in seen) return false
        prefs.edit().putStringSet("avisados", seen + key).apply()
        return true
    }
}
