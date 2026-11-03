package com.example.touchcursor;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Область тачпада: палец двигает курсор (абсолютный режим отсутствует — только дельты,
 * как у классического touchpad). Двойной тап по тачпаду = «клик» (ЛКМ), чтобы можно было
 * выделять текст, не пользуясь кнопками.
 */
public class TouchPadView extends View {

    public interface PadListener {
        void onMove(float dxPx, float dyPx);
        /** Короткий «флик» двумя пальцами вверх/вниз — имитация колеса мыши. */
        void onScroll(int notches);
        void onTap();          // одиночный тап без смещения
        void onDoubleTap();    // двойной тап
    }

    private PadListener listener;
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guide = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private float lastX, lastY;
    private boolean tracking = false;
    private int activePointer = -1;
    private float scrollAccum = 0f;

    private long lastTapTime = 0L;
    private float movedDist = 0f;

    // визуальная точка касания
    private float dotX = -1, dotY = -1;

    public TouchPadView(Context c) { this(c, null); }
    public TouchPadView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        bg.setColor(Color.parseColor("#12161C"));
        bg.setStyle(Paint.Style.FILL);
        guide.setColor(Color.parseColor("#3A4250"));
        guide.setStyle(Paint.Style.STROKE);
        guide.setStrokeWidth(2f);
        setClickable(false);
        setFocusable(true);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        final int action = e.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                activePointer = e.getPointerId(0);
                lastX = e.getX(0);
                lastY = e.getY(0);
                tracking = true;
                movedDist = 0f;
                scrollAccum = 0f;
                dotX = lastX; dotY = lastY;
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_POINTER_DOWN: {
                // второй палец → режим прокрутки колесом
                if (e.getPointerCount() == 2) {
                    scrollAccum = 0f;
                    lastX = (e.getX(0) + e.getX(1)) / 2f;
                    lastY = (e.getY(0) + e.getY(1)) / 2f;
                }
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                if (!tracking) return true;
                if (e.getPointerCount() >= 2) {
                    float midY = (e.getY(0) + e.getY(1)) / 2f;
                    float dy = midY - lastY;
                    lastY = midY;
                    scrollAccum += dy;
                    final int step = 60;   // px на одну «нотчку» колеса
                    while (scrollAccum <= -step) { scrollAccum += step; if (listener != null) listener.onScroll(1); }
                    while (scrollAccum >=  step) { scrollAccum -= step; if (listener != null) listener.onScroll(-1); }
                    dotX = -1;
                    invalidate();
                    return true;
                }

                int idx = e.findPointerIndex(activePointer);
                if (idx < 0) idx = 0;
                float x = e.getX(idx), y = e.getY(idx);
                float dx = x - lastX, dy = y - lastY;
                lastX = x; lastY = y;
                movedDist += Math.abs(dx) + Math.abs(dy);
                dotX = x; dotY = y;
                if ((Math.abs(dx) > 0.5f || Math.abs(dy) > 0.5f) && listener != null) {
                    listener.onMove(dx, dy);
                }
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_POINTER_UP: {
                if (e.getPointerCount() <= 2) {
                    // остались с одним пальцем — обновим якорь, чтобы не было «прыжка»
                    int keep = e.getActionIndex() == 0 ? 1 : 0;
                    lastX = e.getX(keep);
                    lastY = e.getY(keep);
                }
                return true;
            }

            case MotionEvent.ACTION_UP: {
                tracking = false;
                dotX = -1;
                long now = System.currentTimeMillis();
                if (movedDist < 20f && listener != null) {
                    if (now - lastTapTime < 320L) {
                        lastTapTime = 0L;
                        listener.onDoubleTap();
                    } else {
                        lastTapTime = now;
                        final long t = now;
                        postDelayed(() -> {
                            if (lastTapTime == t && listener != null) listener.onTap();
                        }, 320L);
                    }
                }
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_CANCEL: {
                tracking = false;
                dotX = -1;
                invalidate();
                return true;
            }
        }
        return super.onTouchEvent(e);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(bg.getColor());

        // рамка и крестовина — ориентир «центр»
        int w = getWidth(), h = getHeight();
        canvas.drawRect(1, 1, w - 1, h - 1, guide);
        Paint thin = new Paint(guide);
        thin.setAlpha(90);
        canvas.drawLine(w / 2f, 0, w / 2f, h, thin);
        canvas.drawLine(0, h / 2f, w, h / 2f, thin);

        if (dotX >= 0) {
            Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
            dot.setColor(Color.parseColor("#4CAF50"));
            canvas.drawCircle(dotX, dotY, 14f, dot);
        }

        Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
        hint.setColor(Color.parseColor("#5A6472"));
        hint.setTextSize(30f);
        hint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(getString(R.string.pad_hint), w / 2f, h - 24f, hint);
    }

    public void setListener(PadListener l) { this.listener = l; }
}
