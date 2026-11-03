package com.example.touchcursor;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * Экран-планшет: сверху — статус связи, в середине — тачпад, внизу — ДВЕ строки кнопок:
 *   строка 1: [УПРАВЛЕНИЕ ВКЛ/ВЫКЛ]  [Wi-Fi]  [Резерв-1]
 *   строка 2: [ЛКМ]  [ПКМ]           [Резерв-2]
 */
public class MainActivity extends AppCompatActivity
        implements NetClient.Listener, TouchPadView.PadListener {

    public static final String ACTION_DISABLE = "com.example.touchcursor.DISABLE";

    private TouchPadView pad;
    private Button btnControl, btnWifi, btnLmb, btnRmb, btnRes1, btnRes2;
    private TextView status;

    private NetClient net;
    private boolean controlOn = false;
    private boolean connected = false;

    /** Аппаратные кнопки громкости → клики мыши. */
    private MouseButtonMachine volUpMachine, volDownMachine;

    // экранные кнопки ЛКМ/ПКМ — зажаты пальцем?
    private boolean lmbDown = false, rmbDown = false;

    private static final int REQ_PERMS = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        status      = findViewById(R.id.status);
        pad         = findViewById(R.id.pad);
        btnControl  = findViewById(R.id.btn_control);
        btnWifi     = findViewById(R.id.btn_wifi);
        btnLmb      = findViewById(R.id.btn_lmb);
        btnRmb      = findViewById(R.id.btn_rmb);
        btnRes1     = findViewById(R.id.btn_res1);
        btnRes2     = findViewById(R.id.btn_res2);

        pad.setListener(this);

        net = ControlService.ensureNetStatic(this);
        net.setListener(this);

        // --- ЛКМ / ПКМ (экранные) ---
        bindHoldButton(btnLmb, Protocol.DOWN_LEFT, Protocol.UP_LEFT, true);
        bindHoldButton(btnRmb, Protocol.DOWN_RIGHT, Protocol.UP_RIGHT, false);

        btnWifi.setOnClickListener(v -> openWifi());
        btnControl.setOnClickListener(v -> toggleControl());
        btnRes1.setOnClickListener(v -> reserve(1));
        btnRes2.setOnClickListener(v -> reserve(2));

        volUpMachine = new MouseButtonMachine(new MouseButtonMachine.Sink() {
            @Override public void singleClick() { sendCmd(Protocol.DOWN_LEFT); sendCmd(Protocol.UP_LEFT); flash(btnLmb); }
            @Override public void doubleClick() { sendCmd(Protocol.DBL_LEFT); flash(btnLmb); }
            @Override public void pressDown()   { sendCmd(Protocol.DOWN_LEFT); setHeld(btnLmb, true); }
            @Override public void releaseUp()   { sendCmd(Protocol.UP_LEFT);   setHeld(btnLmb, false); }
        });
        volDownMachine = new MouseButtonMachine(new MouseButtonMachine.Sink() {
            @Override public void singleClick() { sendCmd(Protocol.DOWN_RIGHT); sendCmd(Protocol.UP_RIGHT); flash(btnRmb); }
            @Override public void doubleClick() { sendCmd(Protocol.DBL_RIGHT); flash(btnRmb); }
            @Override public void pressDown()   { sendCmd(Protocol.DOWN_RIGHT); setHeld(btnRmb, true); }
            @Override public void releaseUp()   { sendCmd(Protocol.UP_RIGHT);   setHeld(btnRmb, false); }
        });

        askPermissions();

        // Если управление было включено до закрытия приложения — восстанавливаем его
        if (Prefs.controlOn(this)) {
            String h = Prefs.host(this);
            if (!h.isEmpty()) net.connect(h, Protocol.PORT);
            enableControl(true);
        }
        handleIntent(getIntent());
        refreshUi();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && ACTION_DISABLE.equals(intent.getAction())) {
            enableControl(false);
        }
    }

    // ============================ КНОПКИ ГРОМКОСТИ ============================

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (controlOn && isVolumeKey(keyCode)) {
            handleVolume(keyCode, event);
            return true;    // система НЕ изменит громкость
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (controlOn && isVolumeKey(keyCode)) {
            handleVolume(keyCode, event);
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    /**
     * Кнопки громкости → клики мыши. Основной обработчик живёт в ControlService,
     * поэтому логика одинакова для Activity и для фоновых событий; если сервис
     * недоступен — используем локальные машины кнопок.
     */
    private void handleVolume(int keyCode, KeyEvent ev) {
        ControlService cs = ControlService.instance;
        if (cs != null && cs.isIntercepting()) {
            cs.handleKey(keyCode, ev);
            return;
        }
        if (ev.getAction() == KeyEvent.ACTION_DOWN) volumeMachine(keyCode).onDown(ev.getRepeatCount() > 0);
        else if (ev.getAction() == KeyEvent.ACTION_UP) volumeMachine(keyCode).onUp();
    }

    private static boolean isVolumeKey(int k) {
        return k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN;
    }

    private MouseButtonMachine volumeMachine(int k) {
        return k == KeyEvent.KEYCODE_VOLUME_UP ? volUpMachine : volDownMachine;
    }

    // ============================ АКТИВАЦИЯ УПРАВЛЕНИЯ ============================

    private void toggleControl() {
        if (!controlOn) {
            String h = Prefs.host(this);
            if (h.isEmpty()) {
                toast(getString(R.string.warn_no_host));
                openWifi();
                return;
            }
            if (!net.isConnected()) net.connect(h, Protocol.PORT);
            enableControl(true);
        } else {
            enableControl(false);
        }
    }

    private void enableControl(boolean on) {
        controlOn = on;
        Prefs.setControlOn(this, on);

        if (on) {
            ControlService.start(this);
            // не гасим экран, пока палец на тачпаде
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            volUpMachine.reset();
            volDownMachine.reset();
            releaseScreenButtons();
            ControlService.stop(this);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        refreshUi();
    }

    /** Вызывается из сервиса, если он по какой-то причине умер. */
    public static void disableFromOutside(android.content.Context c) {
        Prefs.setControlOn(c, false);
        Intent i = new Intent(c, MainActivity.class);
        i.setAction(ACTION_DISABLE);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        c.startActivity(i);
    }

    // ============================ ЭКРАННЫЕ ЛКМ/ПКМ ============================

    private void bindHoldButton(Button b, final String down, final String up, final boolean left) {
        b.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (!controlOn) { needControl(); return true; }
                    sendCmd(down);
                    if (left) lmbDown = true; else rmbDown = true;
                    setHeld(b, true);
                    v.performClick();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    sendCmd(up);
                    if (left) lmbDown = false; else rmbDown = false;
                    setHeld(b, false);
                    return true;
            }
            return false;
        });
    }

    private void releaseScreenButtons() {
        if (lmbDown) { sendCmd(Protocol.UP_LEFT); lmbDown = false; setHeld(btnLmb, false); }
        if (rmbDown) { sendCmd(Protocol.UP_RIGHT); rmbDown = false; setHeld(btnRmb, false); }
    }

    // ============================ ТАЧПАД ============================

    @Override
    public void onMove(float dxPx, float dyPx) {
        if (!controlOn) return;
        float sx = Prefs.sensX(this), sy = Prefs.sensY(this);
        float dx = dxPx * sx;
        float dy = dyPx * sy * (Prefs.invertY(this) ? -1f : 1f);
        net.sendMove(dx, dy);
    }

    @Override
    public void onScroll(int notches) {
        if (!controlOn) return;
        net.send(Protocol.wheel(notches));
    }

    @Override
    public void onTap() {
        if (!controlOn) return;
        sendCmd(Protocol.DOWN_LEFT);
        sendCmd(Protocol.UP_LEFT);
        flash(btnLmb);
    }

    @Override
    public void onDoubleTap() {
        if (!controlOn) return;
        sendCmd(Protocol.DBL_LEFT);
        flash(btnLmb);
    }

    // ============================ СЕТЬ ============================

    private void openWifi() {
        WifiDialog.show(this, net, connected, new WifiDialog.Result() {
            @Override public void onConnect(String host) {
                net.connect(host, Protocol.PORT);
            }
            @Override public void onDisconnect() {
                net.disconnect();
                connected = false;
                refreshUi();
            }
        });
    }

    @Override public void onConnecting() {
        runOnUiThread(() -> { connected = false; status.setText(R.string.st_connecting); refreshUi(); });
    }

    @Override public void onConnected(String host) {
        runOnUiThread(() -> {
            connected = true;
            Prefs.setHost(this, host);
            toast(getString(R.string.toast_connected, host));
            refreshUi();
            ControlService cs = ControlService.instance;
            if (cs != null) cs.refreshNotification();
        });
    }

    @Override public void onDisconnected(String reason) {
        runOnUiThread(() -> {
            connected = false;
            status.setText(getString(R.string.st_disconnected, reason));
            refreshUi();
        });
    }

    @Override public void onServersFound(java.util.List<String> hosts) {
        runOnUiThread(() -> {
            if (hosts.isEmpty()) {
                toast(getString(R.string.no_pc_found));
            } else if (hosts.size() == 1) {
                String h = NetClient.normalizeHost(hosts.get(0));
                toast(getString(R.string.found_one, h));
                net.connect(h, Protocol.PORT);
            } else {
                CharSequence[] arr = hosts.toArray(new CharSequence[0]);
                new android.app.AlertDialog.Builder(this)
                        .setTitle(R.string.pick_pc)
                        .setItems(arr, (d, w) -> net.connect(
                                NetClient.normalizeHost(hosts.get(w)), Protocol.PORT))
                        .show();
            }
        });
    }

    // ============================ UI ============================

    private void refreshUi() {
        btnControl.setText(controlOn ? R.string.control_on : R.string.control_off);
        btnControl.setBackgroundResource(controlOn ? R.drawable.btn_green : R.drawable.btn_dark);

        btnWifi.setText(connected ? R.string.wifi_on : R.string.wifi_off);
        btnWifi.setBackgroundResource(connected ? R.drawable.btn_blue : R.drawable.btn_dark);

        status.setText(connected
                ? getString(R.string.st_connected, net.getHost(), net.getPortOr(Protocol.PORT))
                : controlOn ? getString(R.string.st_control_no_link) : getString(R.string.st_idle));

        // зарезервированные кнопки — просто помечены «на будущее»
        btnRes1.setEnabled(true);
        btnRes2.setEnabled(true);
    }

    private void setHeld(Button b, boolean held) {
        GradientDrawable d = (GradientDrawable) ContextCompat.getDrawable(
                this, held ? R.drawable.btn_pressed : R.drawable.btn_dark);
        b.setBackground(d);
    }

    private void flash(Button b) {
        setHeld(b, true);
        b.postDelayed(() -> setHeld(b, false), 90);
    }

    private void needControl() {
        toast(getString(R.string.warn_control_off));
    }

    private void reserve(int n) {
        toast(getString(R.string.reserved_toast, n));
    }

    private void sendCmd(String cmd) {
        if (connected || net.isConnected()) net.send(cmd);
        else toast(getString(R.string.warn_no_link));
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // ============================ РАЗРЕШЕНИЯ ============================

    private void askPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_PERMS);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // при уходе с экрана отпускаем всё, что могло быть зажато
        volUpMachine.reset();
        volDownMachine.reset();
        releaseScreenButtons();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();
    }

    @Override
    protected void onDestroy() {
        if (net != null) net.setListener(null);
        super.onDestroy();
    }
}
