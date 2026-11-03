package com.example.touchcursor;

import android.os.Handler;
import android.os.Looper;

/** Мини-хелпер: выполняет Runnable в потоке UI. */
public final class MainThread {
    private static final Handler H = new Handler(Looper.getMainLooper());

    private MainThread() { }

    public static void run(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else H.post(r);
    }

    public static void postDelayed(Runnable r, long ms) { H.postDelayed(r, ms); }

    public static void removeCallbacks(Runnable r) { H.removeCallbacks(r); }
}
