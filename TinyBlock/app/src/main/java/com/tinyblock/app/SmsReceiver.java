package com.tinyblock.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;

/** 收到短信后按设置转发。清单注册，无需服务常驻；总开关与地址模板见「短信」页与「设置」页 */
public class SmsReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null
                || !"android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction())) {
            return;
        }
        if (!WebhookPrefs.get(context, WebhookPrefs.KEY_SMS, false)) return;
        String template = WebhookPrefs.smsUrl(context);
        if (template == null || template.trim().isEmpty()) return;

        SmsMessage[] msgs;
        try {
            msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        } catch (Exception e) {
            msgs = null;
        }
        if (msgs == null || msgs.length == 0) return;

        StringBuilder body = new StringBuilder();
        String sender = null;
        long time = System.currentTimeMillis();
        for (SmsMessage m : msgs) {
            if (m == null) continue;
            body.append(m.getMessageBody());
            if (sender == null) sender = m.getOriginatingAddress();
            if (m.getTimestampMillis() > 0) time = m.getTimestampMillis();
        }
        if (body.length() == 0) return;
        SmsForwarder.forward(template, WebhookPrefs.smsBody(context),
                sender == null ? "" : sender, body.toString(), time);
    }
}
