package com.blackracoon.estimator;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import androidx.core.app.NotificationCompat;

public class NotifReceiver extends BroadcastReceiver {
    static final String CHANNEL_ID = "rs_alerts";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String title   = intent.getStringExtra("title");
        String body    = intent.getStringExtra("body");
        int    notifId = intent.getIntExtra("notifId", 0);

        NotificationManager nm = (NotificationManager)
                ctx.getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "RS Estimator Alerts",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Payment reminders and job alerts");
            nm.createNotificationChannel(ch);
        }

        Intent openApp = ctx.getPackageManager()
                .getLaunchIntentForPackage(ctx.getPackageName());
        if (openApp != null) {
            openApp.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        }
        PendingIntent pi = PendingIntent.getActivity(
                ctx, notifId, openApp != null ? openApp : new Intent(),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.icon)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH);

        nm.notify(notifId, b.build());
    }
}
