package com.tinyblock.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机自启：开关打开时，系统启动完成后拉起前台服务 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (!WebhookPrefs.get(context, WebhookPrefs.KEY_BOOT, false)) return;
        // 和界面启动一致：两个功能都关着就不拉起空服务
        boolean block = WebhookPrefs.get(context, WebhookPrefs.KEY_BLOCK, true);
        boolean hook = WebhookPrefs.get(context, WebhookPrefs.KEY_WEBHOOK, true);
        if (!block && !hook) return;
        context.startForegroundService(new Intent(context, BlockVpnService.class)
                .setAction(BlockVpnService.ACTION_START));
    }
}
