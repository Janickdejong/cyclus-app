package nl.janick.cyclus

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Calendar

/** Dagelijkse herinnering om de temperatuur te meten, ook als de app gesloten is. */
object Reminder {
    const val ACTION_FIRE = "nl.janick.cyclus.HERINNERING"
    private const val CHANNEL = "herinnering"
    private const val PREFS = "cyclus"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(ctx: Context) = prefs(ctx).getBoolean("on", false)

    fun save(ctx: Context, on: Boolean, time: String) {
        prefs(ctx).edit().putBoolean("on", on).putString("time", time).apply()
        schedule(ctx)
    }

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Dagelijkse herinnering", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    fun allowed(ctx: Context): Boolean {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return nm.areNotificationsEnabled()
    }

    private fun alarmIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx, ReminderReceiver::class.java).setAction(ACTION_FIRE)
        return PendingIntent.getBroadcast(
            ctx, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = alarmIntent(ctx)
        am.cancel(pi)
        if (!enabled(ctx)) return

        val parts = (prefs(ctx).getString("time", "07:00") ?: "07:00").split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: 7
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }

        val exactAllowed = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        try {
            if (exactAllowed) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        }
    }

    /** Zelfde controle als in de webapp: staat er voor vandaag al een dag in de lopende cyclus? */
    fun filledToday(ctx: Context): Boolean = try {
        val raw = Storage.read(ctx)
        if (raw == null) {
            false
        } else {
            // De app rekent 'vandaag' in UTC (todayISO), dus hier ook.
            val today = LocalDate.now(ZoneOffset.UTC).toString()
            val cycles = JSONObject(raw).optJSONArray("cycles")
            val last = if (cycles != null && cycles.length() > 0) cycles.optJSONObject(cycles.length() - 1) else null
            val days = last?.optJSONArray("days")
            var found = false
            if (days != null) {
                for (i in 0 until days.length()) {
                    if (days.optJSONObject(i)?.optString("date") == today) found = true
                }
            }
            found
        }
    } catch (e: Exception) {
        false
    }

    fun notify(ctx: Context, title: String, text: String) {
        if (!allowed(ctx)) return
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(
            ctx, 2,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(Color.parseColor("#5B3A4B"))
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(1, n)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Reminder.ACTION_FIRE) {
            if (Reminder.enabled(context) && !Reminder.filledToday(context)) {
                Reminder.notify(context, "🌷 Cyclus", "Vergeet je temperatuur niet te meten!")
            }
        }
        // Altijd de volgende herinnering inplannen (ook na herstart of update).
        Reminder.schedule(context)
    }
}
