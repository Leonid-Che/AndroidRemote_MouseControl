package com.example.touchcursor;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/** Диалог «Связь по Wi-Fi»: адрес компьютера, поиск в сети, чувствительность. */
public final class WifiDialog {

    public interface Result {
        void onConnect(String host);
        void onDisconnect();
    }

    private WifiDialog() { }

    public static void show(Context ctx, NetClient net, boolean connected, Result res) {
        ScrollView scroll = new ScrollView(ctx);
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(ctx, 20);
        box.setPadding(pad, pad, pad, 0);
        scroll.addView(box);

        // --- поле адреса ---
        EditText ip = new EditText(ctx);
        ip.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        ip.setHint(R.string.wifi_ip_hint);
        String cur = net.getHost().isEmpty() ? Prefs.host(ctx) : net.getHost();
        ip.setText(cur);
        ip.setSelection(ip.getText().length());
        box.addView(ip);

        // --- статус ---
        TextView status = new TextView(ctx);
        status.setPadding(0, dp(ctx, 8), 0, 0);
        status.setText(connected
                ? ctx.getString(R.string.wifi_status_on, net.getHost())
                : ctx.getString(R.string.wifi_status_off));
        box.addView(status);

        // --- чувствительность ---
        TextView sl = new TextView(ctx);
        sl.setText(R.string.sensitivity);
        sl.setPadding(0, dp(ctx, 16), 0, dp(ctx, 4));
        box.addView(sl);

        SeekBar seek = new SeekBar(ctx);
        seek.setMax(300);                       // 0.2x .. 3.2x
        float sens = Prefs.sensX(ctx);
        seek.setProgress((int) ((sens - 0.2f) * 100));
        TextView sv = new TextView(ctx);
        sv.setText(ctx.getString(R.string.sens_value, sens));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                float v = 0.2f + p / 100f;
                sv.setText(ctx.getString(R.string.sens_value, v));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) {
                float v = 0.2f + seek.getProgress() / 100f;
                Prefs.setSens(ctx, v, v);
            }
        });
        box.addView(seek);
        box.addView(sv);

        TextView help = new TextView(ctx);
        help.setText(R.string.wifi_help);
        help.setPadding(0, dp(ctx, 16), 0, 0);
        help.setTextSize(12f);
        box.addView(help);

        new AlertDialog.Builder(ctx)
                .setTitle(R.string.wifi_dialog_title)
                .setView(scroll)
                .setPositiveButton(R.string.connect, (d, w) -> {
                    String h = ip.getText().toString().trim();
                    if (!h.isEmpty()) {
                        Prefs.setHost(ctx, h);
                        res.onConnect(h);
                    }
                })
                .setNeutralButton(R.string.find_pc, null)   // заменим ниже, чтобы не закрывать
                .setNegativeButton(connected ? R.string.disconnect : R.string.cancel,
                        (d, w) -> { if (connected) res.onDisconnect(); })
                .show()
                .getButton(AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener(v -> {
                    status.setText(R.string.searching);
                    net.discover(2500);
                });
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
