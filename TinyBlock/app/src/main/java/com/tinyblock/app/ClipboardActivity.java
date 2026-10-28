package com.tinyblock.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** 剪切板页：消息历史，点击一行复制全文（跟随 App 深色主题） */
public class ClipboardActivity extends Activity {

    private static final int BLUE = 0xFF1A73E8;

    private int BG;
    private int TEXT;
    private int MUTED;
    private int CARD;
    private int RED;

    private final List<String[]> items = new ArrayList<>();
    private ListView list;
    private TextView empty;
    private TextView keepView;
    private Adapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        boolean dark = WebhookPrefs.get(this, WebhookPrefs.KEY_DARK, false);
        BG = dark ? 0xFF11161F : 0xFFEEF3FB;
        TEXT = dark ? 0xFFE8EEF9 : 0xFF1B2735;
        MUTED = dark ? 0xFF8B98AD : 0xFF7C8DA6;
        CARD = dark ? 0xFF1A2230 : 0xFFFFFFFF;
        RED = dark ? 0xFFFF7061 : 0xFFD93025;

        Window w = getWindow();
        w.setStatusBarColor(BG);
        View decor = w.getDecorView();
        int flags = decor.getSystemUiVisibility();
        if (dark) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        else flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        decor.setSystemUiVisibility(flags);

        super.onCreate(savedInstanceState);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(CARD);
        bar.setPadding(dp(12), dp(10), dp(12), dp(10));

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        back.setTextColor(TEXT);
        back.setPadding(dp(8), 0, dp(12), dp(2));
        back.setOnClickListener(v -> finish());
        bar.addView(back);

        TextView title = new TextView(this);
        title.setText("剪切板");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTextColor(TEXT);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(title, tlp);

        keepView = new TextView(this);
        keepView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        keepView.setTextColor(BLUE);
        keepView.setPadding(dp(10), dp(6), dp(10), dp(6));
        keepView.setOnClickListener(v -> askKeep());
        bar.addView(keepView);

        TextView clear = new TextView(this);
        clear.setText("清空");
        clear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        clear.setTextColor(RED);
        clear.setPadding(dp(10), dp(6), dp(4), dp(6));
        clear.setOnClickListener(v -> confirmClear());
        bar.addView(clear);

        root.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        list = new ListView(this);
        list.setBackgroundColor(BG);
        list.setDivider(new ColorDrawable(0x00000000));
        list.setDividerHeight(dp(8));
        list.setPadding(dp(14), dp(10), dp(14), dp(18));
        list.setClipToPadding(false);
        list.setCacheColorHint(0);
        list.setSelector(new ColorDrawable(0x00000000));
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= items.size()) return;
            copy(items.get(position)[1]);
        });
        root.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        empty = new TextView(this);
        empty.setText("还没有收到消息\n在电脑或脚本里向手机端口 POST 一条消息试试");
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        empty.setTextColor(MUTED);
        empty.setGravity(Gravity.CENTER);
        empty.setLineSpacing(dp(4), 1f);
        empty.setVisibility(View.GONE);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        elp.setMargins(dp(28), 0, dp(28), 0);
        root.addView(empty, elp);

        setContentView(root);
    }

    private void refresh() {
        items.clear();
        items.addAll(HistoryStore.load(this, Math.max(WebhookPrefs.keep(this), 200)));
        adapter.notifyDataSetChanged();
        boolean isEmpty = items.isEmpty();
        list.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
        empty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        keepView.setText("保留 " + WebhookPrefs.keep(this) + " 条");
    }

    private void copy(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("TinyBlock 消息", text));
            Toast.makeText(this, "已复制到剪切板", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmClear() {
        if (items.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle("清空消息")
                .setMessage("将删除全部 " + items.size() + " 条消息记录，无法恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (d, w) -> {
                    HistoryStore.clear(this);
                    refresh();
                })
                .show();
    }

    private void askKeep() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(WebhookPrefs.keep(this)));
        input.setSelection(input.getText().length());
        input.setPadding(dp(20), dp(10), dp(20), dp(10));

        new AlertDialog.Builder(this)
                .setTitle("保留条数")
                .setMessage("超出后自动删除最早的消息，建议 20–500。")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (d, w) -> {
                    int value;
                    try {
                        value = Integer.parseInt(input.getText().toString().trim());
                    } catch (Exception e) {
                        value = 50;
                    }
                    value = Math.max(1, Math.min(500, value));
                    WebhookPrefs.setKeep(this, value);
                    HistoryStore.trim(this);
                    refresh();
                })
                .show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            TextView time;
            TextView text;
            if (convertView == null) {
                row = new LinearLayout(ClipboardActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(12), dp(12), dp(12), dp(12));
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(CARD);
                bg.setCornerRadius(dp(10));
                row.setBackground(bg);

                time = new TextView(ClipboardActivity.this);
                time.setTextColor(MUTED);
                time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                time.setSingleLine(true);
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                        dp(86), LinearLayout.LayoutParams.WRAP_CONTENT);
                row.addView(time, tlp);

                text = new TextView(ClipboardActivity.this);
                text.setTextColor(TEXT);
                text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                text.setSingleLine(true);
                text.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams xlp = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                row.addView(text, xlp);

                row.setTag(new TextView[]{time, text});
            } else {
                row = (LinearLayout) convertView;
                TextView[] cache = (TextView[]) row.getTag();
                time = cache[0];
                text = cache[1];
            }

            String[] item = items.get(position);
            time.setText(item[2]);
            text.setText(item[1] == null ? "" : item[1].replace('\n', ' '));
            return row;
        }
    }
}
