package com.example.touchcursor;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Сетевой клиент: TCP-соединение с компьютером + UDP-обнаружение в локальной сети.
 *
 * Все сетевые операции выполняются в фоновых потоках; колбэки приходят в главный поток
 * через {@link MainThread}.
 */
public class NetClient {

    private static final String TAG = "NetClient";
    private static final int CONNECT_TIMEOUT_MS = 2500;
    private static final long PING_PERIOD_SEC = 5;

    public interface Listener {
        void onConnecting();
        void onConnected(String host);
        void onDisconnected(String reason);
        /** @param hosts список "ip:порт" найденных серверов */
        void onServersFound(List<String> hosts);
    }

    private final Context ctx;
    private Listener listener;

    private volatile Socket socket;
    private volatile int port = Protocol.PORT;
    private volatile OutputStream out;
    private volatile String host = "";
    private volatile boolean wantConnect = false;

    private ScheduledExecutorService io;
    private ScheduledFuture<?> pingTask;

    // ---- очередь отправки (чтобы не терять клики при кратковременных лагах) ----
    private final Object queueLock = new ArrayList<String>();
    private final ArrayList<String> queue = new ArrayList<>();
    private static final int QUEUE_LIMIT = 64;
    /** Движение — «вытесняемое» событие: храним только накопленную дельту. */
    private float pendingDx = 0f, pendingDy = 0f;

    public NetClient(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.io = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "net-io");
            t.setDaemon(true);
            return t;
        });
    }

    public void setListener(Listener l) { this.listener = l; }

    public boolean isConnected() { return socket != null && !socket.isClosed(); }

    public String getHost() { return host; }

    /** Порт, на котором установлено (или ожидается) соединение. */
    public int getPortOr(int def) { return port != 0 ? port : def; }

    // ============================ ПОДКЛЮЧЕНИЕ ============================

    public void connect(String host, int port) {
        this.host = normalizeHost(host);
        this.port = port;
        this.wantConnect = true;
        post(() -> { if (listener != null) listener.onConnecting(); });
        io.execute(() -> openSocket(this.host, port));
    }

    public void disconnect() {
        wantConnect = false;
        io.execute(() -> closeSocket("manual"));
    }

    /** Переподключение по текущему адресу (используется кнопкой Wi-Fi). */
    public void reconnect() {
        if (host == null || host.isEmpty()) {
            post(() -> { if (listener != null) listener.onDisconnected("адрес не задан"); });
            return;
        }
        connect(host, Protocol.PORT);
    }

    private void openSocket(String h, int port) {
        try {
            closeSocketQuietly();
            Socket s = new Socket();
            s.connect(new InetSocketAddress(h, port), CONNECT_TIMEOUT_MS);
            s.setTcpNoDelay(true);      // важнее скорость, чем «пакеты целиком»
            s.setKeepAlive(true);

            socket = s;
            out = s.getOutputStream();
            startReader(s);
            startPing();

            post(() -> { if (listener != null) listener.onConnected(h); });
            send(Protocol.HELLO);
        } catch (IOException e) {
            Log.w(TAG, "connect failed: " + e);
            final String msg = "нет связи с " + h + " (" + e.getLocalizedMessage() + ")";
            post(() -> { if (listener != null) listener.onDisconnected(msg); });
        }
    }

    private void startReader(Socket s) {
        io.execute(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    Log.d(TAG, "<- " + line);   // сервер может слать ACK / статус
                }
            } catch (IOException ignored) {
            } finally {
                if (socket == s) closeSocket("сервер закрыл соединение");
            }
        });
    }

    private void startPing() {
        if (pingTask != null) pingTask.cancel(false);
        pingTask = io.scheduleWithFixedDelay(() -> send(Protocol.PING),
                PING_PERIOD_SEC, PING_PERIOD_SEC, TimeUnit.SECONDS);
    }

    private void closeSocket(String reason) {
        closeSocketQuietly();
        post(() -> { if (listener != null) listener.onDisconnected(reason); });
    }

    private void closeSocketQuietly() {
        if (pingTask != null) { pingTask.cancel(false); pingTask = null; }
        try { if (socket != null) socket.close(); } catch (IOException ignored) { }
        socket = null;
        out = null;
    }

    public void shutdown() {
        wantConnect = false;
        io.shutdownNow();
        closeSocketQuietly();
    }

    // ============================ ОТПРАВКА ============================

    public void send(String cmd) {
        synchronized (queueLock) {
            if (queue.size() < QUEUE_LIMIT) queue.add(cmd);
        }
        io.execute(this::flush);
    }

    /** Накопительное смещение: сливаем дельты, чтобы не захлёбывать сеть. */
    public void sendMove(float dx, float dy) {
        boolean needFlush;
        synchronized (queueLock) {
            needFlush = queue.isEmpty();
            pendingDx += dx;
            pendingDy += dy;
        }
        if (needFlush) io.execute(this::flush);
    }

    private void flush() {
        OutputStream o = out;
        if (o == null) return;

        float dx, dy;
        List<String> cmds = new ArrayList<>(8);
        synchronized (queueLock) {
            cmds.addAll(queue);
            queue.clear();
            dx = pendingDx; pendingDx = 0f;
            dy = pendingDy; pendingDy = 0f;
        }
        if (dx != 0f || dy != 0f) cmds.add(Protocol.move(dx, dy));
        if (cmds.isEmpty()) return;

        StringBuilder sb = new StringBuilder(cmds.size() * 12);
        for (String c : cmds) sb.append(c).append('\n');
        byte[] data = sb.toString().getBytes(StandardCharsets.UTF_8);

        try {
            o.write(data);
            o.flush();
        } catch (IOException e) {
            Log.w(TAG, "write failed: " + e);
            closeSocket("обрыв связи");
        }
    }

    // ============================ ОБНАРУЖЕНИЕ ============================

    /** UDP-широковещание: находит компьютер с запущенным сервером в той же подсети. */
    public void discover(final int timeoutMs) {
        io.execute(() -> {
            final List<String> found = new ArrayList<>();
            DatagramSocket ds = null;
            WifiManager.MulticastLock ml = null;
            try {
                WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    ml = wm.createMulticastLock("touchcursor");
                    ml.acquire();
                }
                ds = new DatagramSocket();
                ds.setBroadcast(true);
                ds.setSoTimeout(timeoutMs);

                byte[] req = Protocol.DISCOVERY_MAGIC.getBytes(StandardCharsets.UTF_8);
                InetAddress bcast = InetAddress.getByName("255.255.255.255");
                ds.send(new DatagramPacket(req, req.length, bcast, Protocol.DISCOVERY_PORT));

                long end = System.currentTimeMillis() + timeoutMs;
                byte[] buf = new byte[256];
                while (System.currentTimeMillis() < end) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try {
                        ds.receive(p);
                    } catch (IOException e) {
                        break; // таймаут
                    }
                    String resp = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8).trim();
                    if (resp.startsWith(Protocol.DISCOVERY_REPLY)) {
                        String ip = p.getAddress().getHostAddress();
                        String entry = ip + ":" + Protocol.PORT + (resp.contains("|") ?
                                "  " + resp.substring(resp.indexOf('|') + 1) : "");
                        if (!containsIp(found, ip)) found.add(entry);
                    }
                }
            } catch (IOException e) {
                Log.w(TAG, "discover failed: " + e);
            } finally {
                if (ds != null) ds.close();
                if (ml != null && ml.isHeld()) ml.release();
            }
            post(() -> { if (listener != null) listener.onServersFound(found); });
        });
    }

    private static boolean containsIp(List<String> list, String ip) {
        for (String s : list) if (s.startsWith(ip + ":")) return true;
        return false;
    }

    // ============================ УТИЛИТЫ ============================

    /** Разрешает форматы "192.168.1.5", "192.168.1.5:8721", "pc.local". */
    public static String normalizeHost(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        int colon = s.lastIndexOf(':');
        if (colon > 0 && s.indexOf('.') < colon) { // порт указан
            try { Integer.parseInt(s.substring(colon + 1)); return s.substring(0, colon); }
            catch (NumberFormatException ignored) { }
        }
        return s;
    }

    public static int parsePort(String raw, int def) {
        if (raw == null) return def;
        int colon = raw.lastIndexOf(':');
        if (colon > 0) {
            try { return Integer.parseInt(raw.substring(colon + 1).trim()); }
            catch (NumberFormatException ignored) { }
        }
        return def;
    }

    private void post(Runnable r) { MainThread.run(r); }

    public String describeTarget() {
        return host.isEmpty() ? "" : host.toUpperCase(Locale.US);
    }
}
