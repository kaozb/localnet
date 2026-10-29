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
        context.startForegroundService(new Intent(context, BlockVpnService.class)
                .setAction(BlockVpnService.ACTION_START));
    }
}
