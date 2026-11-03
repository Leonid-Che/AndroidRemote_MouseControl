package com.example.touchcursor;

/**
 * Автомат для одной аппаратной кнопки: различает
 *  • одиночное нажатие            → одинарный клик мыши
 *  • двойное нажатие              → двойной клик мыши
 *  • удержание (>= HOLD_MS)       → «кнопка мыши зажата», отпускание → отпущена
 *
 * Все временные проверки выполняются через {@link MainThread}, поэтому автомат
 * должен создаваться и вызываться из потока UI.
 */
public class MouseButtonMachine {

    public static final int HOLD_MS = 400;      // порог «удержания»
    public static final int DOUBLE_WINDOW_MS = 350; // окно для второго нажатия

    public interface Sink {
        void singleClick();
        void doubleClick();
        void pressDown();     // начало удержания
        void releaseUp();     // конец удержания
    }

    private final Sink sink;

    private boolean pressed = false;    // физически удерживается
    private boolean holding = false;    // перешагнули порог удержания, мы уже послали DOWN
    private long downAt = 0L;
    private boolean pendingSingle = false;  // тап отпущен, ждём возможный второй

    private final Runnable holdCheck = () -> {
        if (pressed && !holding) {
            holding = true;
            sink.pressDown();
        }
    };

    private final Runnable singleTimeout = () -> {
        if (pendingSingle) {
            pendingSingle = false;
            sink.singleClick();
        }
    };

    public MouseButtonMachine(Sink sink) { this.sink = sink; }

    /** @param repeat true — системная автоповторная генерация ACTION_DOWN при долгом удержании. */
    public void onDown(boolean repeat) {
        if (!pressed) {
            pressed = true;
            holding = false;
            downAt = System.currentTimeMillis();
            MainThread.postDelayed(holdCheck, HOLD_MS);

            if (pendingSingle) {
                // второй тап в окне → двойной клик
                pendingSingle = false;
                MainThread.removeCallbacks(singleTimeout);
                sink.doubleClick();
                // состояние "pressed" осталось: короткое второе нажатие не должно
                // порождать удержание, если его быстро отпустят — см. onUp()
                downAt = Long.MIN_VALUE;   // помечаем: этот тап уже «сыграл» как двойной
            }
        }
        // repeat-события игнорируем: удержание определяется по таймеру holdCheck
    }

    public void onUp() {
        if (!pressed) return;
        pressed = false;
        MainThread.removeCallbacks(holdCheck);

        if (holding) {
            holding = false;
            sink.releaseUp();
            return;
        }
        if (downAt == Long.MIN_VALUE) {
            // это было завершение двойного клика — ничего больше не шлём
            return;
        }
        long dur = System.currentTimeMillis() - downAt;
        if (dur <= HOLD_MS) {
            pendingSingle = true;
            MainThread.postDelayed(singleTimeout, DOUBLE_WINDOW_MS);
        }
    }

    /** Принудительно сбросить состояние (выключение управления, потеря фокуса). */
    public void reset() {
        MainThread.removeCallbacks(holdCheck);
        MainThread.removeCallbacks(singleTimeout);
        if (pendingSingle) { pendingSingle = false; }
        if (holding) { holding = false; sink.releaseUp(); }
        pressed = false;
        downAt = 0L;
    }

    public boolean isHolding() { return holding; }
}
