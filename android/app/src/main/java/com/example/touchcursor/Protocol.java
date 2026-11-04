package com.example.touchcursor;

/**
 * Протокол обмена с компьютером.
 *
 * Транспорт: TCP-сокет (порт {@link #PORT}), сообщения разделены '\n'.
 * Каждое сообщение — одна команда, например:
 *   M 123.4 -56.7          — смещение курсора на дельту (в «пикселях» экрана телефона)
 *   DL / DR                — нажата левая / правая кнопка мыши
 *   UL / UR                — отпущена левая / правая кнопка мыши
 *   DC                     — двойной клик левой кнопкой
 *   DD                     — двойной клик правой кнопкой
 *   W 3                    — колесо мыши
 *   PING                   — проверка связи
 *   HELLO TouchCursor/1.0  — представление при подключении
 */
public final class Protocol {

    /** TCP-порт сервера на компьютере. */
    public static final int PORT = 8721;

    /** UDP-порт для автоматического поиска компьютера в локальной сети. */
    public static final int DISCOVERY_PORT = 8722;

    public static final String DISCOVERY_MAGIC = "TOUCHCURSOR_DISCOVER";
    public static final String DISCOVERY_REPLY = "TOUCHCURSOR_SERVER";

    // Команды
    public static final String MOVE        = "M";
    public static final String DOWN_LEFT   = "DL";
    public static final String UP_LEFT     = "UL";
    public static final String DOWN_RIGHT  = "DR";
    public static final String UP_RIGHT    = "UR";
    public static final String DBL_LEFT    = "DC";
    public static final String DBL_RIGHT   = "DD";
    public static final String WHEEL       = "W";
    public static final String PING        = "PING";
    public static final String HELLO       = "HELLO TouchCursor/1.0";

    private Protocol() { }

    public static String move(float dx, float dy) {
        return MOVE + " " + fmt(dx) + " " + fmt(dy);
    }

    public static String wheel(int notches) {
        return WHEEL + " " + notches;
    }

    private static String fmt(float v) {
        // '.' как разделитель — так проще парсить на стороне Python
        String s = String.format(java.util.Locale.US, "%.2f", v);
        if (s.equals("-0.00")) s = "0.00";
        return s;
    }
}
