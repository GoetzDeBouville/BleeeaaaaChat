package ru.yandexpraktikum.blechat.presentation.notifications

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import ru.yandexpraktikum.blechat.R
import javax.inject.Inject

private const val CHANNEL_ID = "channel_id"
private const val NOTIFICATION_ID = 1234

class NotificationsHelperImpl
@Inject constructor(
    @ApplicationContext private val context: Context,
    private val notificationManager: NotificationManagerCompat
) : NotificationsHelper {

    override fun notifyOnMessageReceived(title: String, message: String) {
        val bigTextStyle = NotificationCompat.BigTextStyle()
            .bigText(message)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setStyle(bigTextStyle)
            .setSmallIcon(R.drawable.bluetooth_ic)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .build()

        @Suppress("MissingPermission")
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}