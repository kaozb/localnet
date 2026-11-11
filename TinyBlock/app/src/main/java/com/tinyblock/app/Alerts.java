package com.tinyblock.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;

/** 消息提醒：通知、声音、震动、持续响铃、悬浮条 */
public class Alerts {

    public static final String CHANNEL_MESSAGES = "messages";

    private static final int RING_NOTIFICATION_ID = 1001;
    private static final long RING_DURATION_MS = 60_000;
    private static final long RING_COOLDOWN_MS = 10_000;

    private static int nextMessageId = 2000;

    private final Context ctx;
    private final NotificationManager nm;
    private final FloatingManager floating;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable ringTimeout = this::stopRingContinuous;

    private Ringtone oneShot;
    private MediaPlayer loopPlayer;
    private Vibrator loopVibrator;
    private volatile boolean ringing;
    private long lastRingEndAt;
    private String lastText = "";

    public Alerts(Context context) {
        this.ctx = context.getApplicationContext();
        this.nm = (NotificationManager) this.ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        this.floating = new FloatingManager(this.ctx, this::acknowledge);
        ensureChannel();
    }

    public void deliver(String text, String time) {
        lastText = text == null ? "" : text;

        boolean loop = WebhookPrefs.get(ctx, WebhookPrefs.KEY_RING_LOOP, false);
        long sinceEnd = System.currentTimeMillis() - lastRingEndAt;
        if (loop && !ringing && sinceEnd > RING_COOLDOWN_MS) {
            startRingContinuous();
            postMessage(text, time, RING_NOTIFICATION_ID, true);
        } else if (ringing) {
            postMessage(text, time, nextMessageId++, false);
        } else {
            playAlert();
            postMessage(text, time, nextMessageId++, false);
        }

        floating.show("新消息", text, time, ringing ? RING_DURATION_MS : 4000, ringing);
    }

    public void acknowledge() {
        stopRingContinuous();
        floating.dismiss();
    }

    public void stopAll() {
        stopRingContinuous();
        stopOneShot();
        floating.shutdown();
        try {
            nm.cancel(RING_NOTIFICATION_ID);
        } catch (Exception ignored) {
        }
    }

    /** 在设置里试听当前铃声 */
    public void preview() {
        stopOneShot();
        Uri uri = ringtoneUri();
        if (uri == null) return;
        try {
            oneShot = RingtoneManager.getRingtone(ctx, uri);
            if (oneShot == null) return;
            oneShot.setAudioAttributes(notificationAudio());
            oneShot.play();
            ui.postDelayed(this::stopOneShot, 5000);
        } catch (Exception ignored) {
        }
    }

    public static String ringtoneTitle(Context c) {
        String value = WebhookPrefs.ringtone(c);
        if (value == null) return "默认通知铃声";
        if (value.isEmpty()) return "静音";
        try {
            Ringtone r = RingtoneManager.getRingtone(c, Uri.parse(value));
            String title = r == null ? null : r.getTitle(c);
            return title == null || title.isEmpty() ? "自定义铃声" : title;
        } catch (Exception e) {
            return "自定义铃声";
        }
    }

    private void playAlert() {
        if (WebhookPrefs.get(ctx, WebhookPrefs.KEY_SOUND, true)) {
            Uri uri = ringtoneUri();
            if (uri != null) {
                try {
                    oneShot = RingtoneManager.getRingtone(ctx, uri);
                    if (oneShot != null) {
                        oneShot.setAudioAttributes(notificationAudio());
                        oneShot.play();
                        ui.postDelayed(this::stopOneShot, 5000);
                    }
                } catch (Exception ignored) {
                }
            }
        }
        if (WebhookPrefs.get(ctx, WebhookPrefs.KEY_VIBRATE, true)) {
            try {
                Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null && v.hasVibrator()) {
                    v.vibrate(VibrationEffect.createOneShot(180,
                            VibrationEffect.DEFAULT_AMPLITUDE));
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void startRingContinuous() {
        ringing = true;
        ui.removeCallbacks(ringTimeout);

        if (WebhookPrefs.get(ctx, WebhookPrefs.KEY_SOUND, true)) {
            Uri uri = ringtoneUri();
            if (uri != null) {
                try {
                    loopPlayer = new MediaPlayer();
                    loopPlayer.setDataSource(ctx, uri);
                    loopPlayer.setAudioAttributes(notificationAudio());
                    loopPlayer.setLooping(true);
                    loopPlayer.prepare();
                    loopPlayer.start();
                } catch (Exception e) {
                    stopLoopPlayer();
                }
            }
        }

        if (WebhookPrefs.get(ctx, WebhookPrefs.KEY_VIBRATE, true)) {
            try {
                loopVibrator = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
                if (loopVibrator != null && loopVibrator.hasVibrator()) {
                    loopVibrator.vibrate(VibrationEffect.createWaveform(
                            new long[]{0, 600, 300, 600}, 0));
                }
            } catch (Exception ignored) {
            }
        }

        ui.postDelayed(ringTimeout, RING_DURATION_MS);
    }

    private void stopRingContinuous() {
        ui.removeCallbacks(ringTimeout);
        boolean was = ringing;
        ringing = false;
        stopLoopPlayer();
        if (loopVibrator != null) {
            try {
                loopVibrator.cancel();
            } catch (Exception ignored) {
            }
            loopVibrator = null;
        }
        if (was) {
            lastRingEndAt = System.currentTimeMillis();
            try {
                nm.cancel(RING_NOTIFICATION_ID);
            } catch (Exception ignored) {
            }
        }
    }

    private void stopLoopPlayer() {
        if (loopPlayer != null) {
            try {
                if (loopPlayer.isPlaying()) loopPlayer.stop();
            } catch (Exception ignored) {
            }
            try {
                loopPlayer.release();
            } catch (Exception ignored) {
            }
            loopPlayer = null;
        }
    }

    private void stopOneShot() {
        if (oneShot != null) {
            try {
                if (oneShot.isPlaying()) oneShot.stop();
            } catch (Exception ignored) {
            }
            oneShot = null;
        }
    }

    private void postMessage(String text, String time, int id, boolean ringingStyle) {
        if (!WebhookPrefs.get(ctx, WebhookPrefs.KEY_NOTIFY, true)) return;
        String body = text == null ? "" : text;
        if (body.length() > 2000) body = body.substring(0, 2000);

        Intent intent = new Intent(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(ctx, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = new Notification.Builder(ctx, CHANNEL_MESSAGES)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(ringingStyle ? "新消息 · 持续响铃" : "TinyBlock 消息")
                .setContentText(preview(body))
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .setAutoCancel(true)
                .setContentIntent(content)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setOnlyAlertOnce(true);

        if (ringingStyle) {
            b.setOngoing(false);
            b.setAutoCancel(false);
            Intent ack = new Intent(ctx, BlockVpnService.class)
                    .setAction(BlockVpnService.ACTION_STOP_RING);
            PendingIntent ackPi = PendingIntent.getService(ctx, 1, ack,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(ctx, R.drawable.ic_notification),
                    "已确认", ackPi).build());
            b.setDeleteIntent(ackPi);
        }

        if (time != null && !time.isEmpty() && !ringingStyle) {
            b.setSubText(time);
        }

        try {
            nm.notify(id, b.build());
        } catch (Exception ignored) {
        }
    }

    private static String preview(String text) {
        String s = text.replace('\n', ' ').trim();
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }

    private Uri ringtoneUri() {
        String value = WebhookPrefs.ringtone(ctx);
        if (value == null) return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        if (value.isEmpty()) return null;
        try {
            return Uri.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    private AudioAttributes notificationAudio() {
        return new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_MESSAGES, "消息提醒",
                NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("接收 Webhook 消息时的提醒");
        ch.setSound(null, null);
        ch.enableVibration(false);
        ch.setShowBadge(true);
        try {
            nm.createNotificationChannel(ch);
        } catch (Exception ignored) {
        }
    }
}
