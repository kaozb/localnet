package com.tinyblock.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 消息落库与合并：2 秒内的多条消息合并成一条提醒 */
public class MessageCenter {

    private final Context ctx;
    private final Alerts alerts;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<String> batch = new ArrayList<>();

    private long lastDeliverAt;
    private String lastTime = "";

    private final Runnable flushRunnable = this::flush;

    public MessageCenter(Context context, Alerts alerts) {
        this.ctx = context.getApplicationContext();
        this.alerts = alerts;
    }

    public void onMessage(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) return;

        String time = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date());
        HistoryStore.append(ctx, "Webhook", value, time);

        boolean immediate = false;
        boolean schedule = false;
        synchronized (batch) {
            if (batch.isEmpty()) {
                immediate = SystemClock.elapsedRealtime() - lastDeliverAt >= 3000;
                schedule = !immediate;
            }
            if (batch.size() < 100) batch.add(value);
            lastTime = time;
        }

        if (immediate) ui.post(flushRunnable);
        else if (schedule) ui.postDelayed(flushRunnable, 2000);
    }

    public void stop() {
        ui.removeCallbacks(flushRunnable);
        synchronized (batch) {
            batch.clear();
        }
    }

    private void flush() {
        String text;
        int count;
        String time;
        synchronized (batch) {
            if (batch.isEmpty()) return;
            count = batch.size();
            if (count == 1) {
                text = batch.get(0);
            } else {
                StringBuilder sb = new StringBuilder("收到 ").append(count).append(" 条消息\n");
                int start = Math.max(0, batch.size() - 5);
                for (int i = start; i < batch.size(); i++) {
                    if (i > start) sb.append('\n');
                    sb.append(batch.get(i));
                }
                text = sb.toString();
            }
            batch.clear();
            time = lastTime;
        }
        lastDeliverAt = SystemClock.elapsedRealtime();
        alerts.deliver(text, time);
    }
}
