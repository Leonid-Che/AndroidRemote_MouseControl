#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
TouchCursor PC Server
=====================
Серверная часть программы управления курсором Windows 10 со смартфона Android.

Протокол (совпадает с android/app/src/main/java/.../Protocol.java):
  Транспорт — TCP, порт 8721, сообщения разделены '\\n':
    M <dx> <dy>   — смещение курсора (абсолютные пиксели)
    DL / UL       — левая кнопка мыши нажата / отпущена
    DR / UR       — правая кнопка мыши нажата / отпущена
    DC            — двойной клик левой
    DD            — двойной клик правой
    W <notches>   — колесо мыши (+ вверх / - вниз... см. ниже)
    PING          — проверка связи (сервер отвечает PONG)
    HELLO ...     — представление клиента (сервер отвечает OK)

  UDP-обнаружение, порт 8722:
    клиент шлёт "TOUCHCURSOR_DISCOVER" → сервер отвечает
    "TOUCHCURSOR_SERVER|<имя компьютера>".

Требования: Python 3.8+, pyautogui (pip install pyautogui).
Запуск:      python touchcursor_server.py [--port 8721] [--udp-port 8722]
             [--scale 1.5] [--failsafe]

Безопасность: сервер принимает соединения только из локальной подсети
(127.0.0.0/8, 10/8, 172.16/12, 192.168/16, 169.254/16).
"""

import argparse
import ipaddress
import os
import socket
import sys
import threading
import time

try:
    import pyautogui
except ImportError:
    sys.stderr.write(
        "Не найден модуль pyautogui.\n"
        "Установите его командой:  pip install pyautogui\n")
    sys.exit(1)

DEFAULT_PORT = 8721
DEFAULT_UDP_PORT = 8722
DISCOVERY_MAGIC = b"TOUCHCURSOR_DISCOVER"
DISCOVERY_REPLY_PREFIX = "TOUCHCURSOR_SERVER"

MOVE_EPS = 0.5           # накопление дробных пикселей до целых
MAX_WHEEL_CLICKS = 25    # защита от «безумной прокрутки»


def log(msg: str) -> None:
    print(time.strftime("[%H:%M:%S] ") + msg, flush=True)


def is_local(addr: str) -> bool:
    """Разрешаем подключение только из локальных сетей."""
    try:
        ip = ipaddress.ip_address(addr)
    except ValueError:
        return False
    return ip.is_loopback or ip.is_private or ip.is_link_local


class MouseController:
    """Однопоточный исполнитель команд мыши (все вызовы сериализуются замком)."""

    def __init__(self, scale: float = 1.0):
        self.scale = scale
        self.lock = threading.Lock()
        self.frac_x = 0.0
        self.frac_y = 0.0
        # текущее состояние кнопок (для гарантированного отпускания при обрыве)
        self.pressed_buttons: "set[str]" = set()

    # ---------- движение ----------
    def move(self, dx: float, dy: float) -> None:
        if self.scale != 1.0:
            dx *= self.scale
            dy *= self.scale
        with self.lock:
            self.frac_x += dx
            self.frac_y += dy
            ix = int(round(self.frac_x))
            iy = int(round(self.frac_y))
            if ix == 0 and iy == 0:
                return
            self.frac_x -= ix
            self.frac_y -= iy
            try:
                pyautogui.moveRel(ix, iy, duration=0)
            except pyautogui.FailSafeException:
                raise
            except Exception as e:  # курсор мог «упереться» и т.п.
                log("move error: %s" % e)

    # ---------- кнопки ----------
    def _press(self, button: str) -> None:
        with self.lock:
            self.pressed_buttons.add(button)
            try:
                pyautogui.mouseDown(button=button)
            except Exception as e:
                log("mouseDown(%s) error: %s" % (button, e))

    def _release(self, button: str) -> None:
        with self.lock:
            self.pressed_buttons.discard(button)
            try:
                pyautogui.mouseUp(button=button)
            except Exception as e:
                log("mouseUp(%s) error: %s" % (button, e))

    def left_down(self) -> None:
        self._press("left")

    def left_up(self) -> None:
        self._release("left")

    def right_down(self) -> None:
        self._press("right")

    def right_up(self) -> None:
        self._release("right")

    def double_left(self) -> None:
        with self.lock:
            try:
                pyautogui.doubleClick(button="left")
            except Exception as e:
                log("doubleClick error: %s" % e)

    def double_right(self) -> None:
        with self.lock:
            try:
                pyautogui.doubleClick(button="right")
            except Exception as e:
                log("right doubleClick error: %s" % e)

    # ---------- колесо ----------
    def wheel(self, notches: int) -> None:
        notches = max(-MAX_WHEEL_CLICKS, min(MAX_WHEEL_CLICKS, notches))
        if notches == 0:
            return
        with self.lock:
            try:
                # в pyautogui положительное значение = колесо «на себя» (вниз по списку),
                # а на телефоне «свайп вверх» означает прокрутку вперёд — инвертируем.
                pyautogui.scroll(-notches)
            except Exception as e:
                log("scroll error: %s" % e)

    # ---------- аварийный сброс ----------
    def release_all(self) -> None:
        for button in list(self.pressed_buttons):
            self._release(button)


class CommandExecutor(threading.Thread):
    """
    Очередь команд: сетевой поток только кладёт строки в очередь,
    этот поток их исполняет. Если сеть быстрее мыши — команды движения
    накапливаются (дельты суммируются), а клики не теряются.
    """

    def __init__(self, mouse: MouseController):
        super().__init__(daemon=True, name="cmd-exec")
        self.mouse = mouse
        self.cv = threading.Condition()
        self.queue: "list[tuple]" = []
        self.alive = True

    def submit(self, cmd: tuple) -> None:
        with self.cv:
            # дельты движения сливаем с последней незакрытой MOVE-записью
            if cmd[0] == "M" and self.queue and self.queue[-1][0] == "M":
                last = self.queue[-1]
                self.queue[-1] = ("M", last[1] + cmd[1], last[2] + cmd[2])
            else:
                self.queue.append(cmd)
            self.cv.notify()

    def run(self) -> None:
        while self.alive:
            with self.cv:
                while not self.queue and self.alive:
                    self.cv.wait(0.5)
                if not self.alive:
                    break
                batch, self.queue = self.queue, []
            for cmd in batch:
                try:
                    self._exec(cmd)
                except pyautogui.FailSafeException:
                    log("FAIL-SAFE: курсор у угла экрана, команда проигнорирована.")
                    self.mouse.release_all()
                except Exception as e:
                    log("cmd %r error: %s" % (cmd, e))

    def stop(self) -> None:
        self.alive = False
        with self.cv:
            self.cv.notify_all()

    def _exec(self, cmd: tuple) -> None:
        kind = cmd[0]
        if kind == "RESET":
            self.mouse.release_all()
        elif kind == "M":
            self.mouse.move(cmd[1], cmd[2])
        elif kind == "DL":
            self.mouse.left_down()
        elif kind == "UL":
            self.mouse.left_up()
        elif kind == "DR":
            self.mouse.right_down()
        elif kind == "UR":
            self.mouse.right_up()
        elif kind == "DC":
            self.mouse.double_left()
        elif kind == "DD":
            self.mouse.double_right()
        elif kind == "W":
            self.mouse.wheel(cmd[1])


def parse_line(line: str):
    """Разбирает текстовую команду протокола; возвращает ('RAW', text) для PING/HELLO."""
    parts = line.split()
    if not parts:
        return None
    c = parts[0].upper()
    try:
        if c == "M" and len(parts) >= 3:
            return ("M", float(parts[1]), float(parts[2]))
        if c in ("DL", "UL", "DR", "UR", "DC", "DD"):
            return (c,)
        if c == "W" and len(parts) >= 2:
            return ("W", int(float(parts[1])))
    except (ValueError, IndexError):
        return None
    if c in ("PING", "HELLO"):
        return ("RAW", c)
    return None


class ClientSession(threading.Thread):
    def __init__(self, sock: socket.socket, addr, executor: CommandExecutor):
        super().__init__(daemon=True, name="client-%s" % addr[0])
        self.sock = sock
        self.addr = addr
        self.executor = executor
        self.alive = True

    def run(self) -> None:
        log("клиент подключён: %s:%d" % self.addr)
        buf = b""
        try:
            self.sock.settimeout(30.0)
            while self.alive:
                try:
                    data = self.sock.recv(4096)
                except socket.timeout:
                    continue
                if not data:
                    break
                buf += data
                while b"\n" in buf:
                    line, buf = buf.split(b"\n", 1)
                    self._handle(line.decode("utf-8", "replace").strip())
        except OSError:
            pass
        finally:
            self.close()
            log("клиент отключился: %s" % self.addr[0])

    def _handle(self, line: str) -> None:
        if not line:
            return
        cmd = parse_line(line)
        if cmd is None:
            return
        if cmd[0] == "RAW":
            # служебные сообщения — отвечаем сразу, минуя очередь
            try:
                reply = "PONG\n" if cmd[1] == "PING" else "OK\n"
                self.sock.sendall(reply.encode("ascii"))
            except OSError:
                pass
            return
        self.executor.submit(cmd)

    def close(self) -> None:
        self.alive = False
        try:
            self.sock.close()
        except OSError:
            pass
        # если оборвалась связь — обязательно отжать все кнопки
        self.executor.submit(("RESET",))


class DiscoveryServer(threading.Thread):
    """UDP-ответчик для автоматического поиска сервера в локальной сети."""

    def __init__(self, udp_port: int):
        super().__init__(daemon=True, name="discovery")
        self.udp_port = udp_port
        self.sock = None
        self.alive = True
        self.hostname = os.environ.get("COMPUTERNAME") or socket.gethostname()

    def run(self) -> None:
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            s.bind(("0.0.0.0", self.udp_port))
            self.sock = s
        except OSError as e:
            log("UDP-порт %d занят (%s) — автопоиск недоступен." % (self.udp_port, e))
            return
        log("UDP-обнаружение запущено на порту %d" % self.udp_port)
        while self.alive:
            try:
                data, addr = s.recvfrom(1024)
            except OSError:
                break
            if DISCOVERY_MAGIC in data and is_local(addr[0]):
                reply = ("%s|%s" % (DISCOVERY_REPLY_PREFIX, self.hostname)).encode("utf-8")
                try:
                    s.sendto(reply, addr)
                except OSError:
                    pass
        log("UDP-обнаружение остановлено")

    def stop(self) -> None:
        self.alive = False
        if self.sock:
            try:
                self.sock.close()
            except OSError:
                pass


class TouchCursorServer:
    def __init__(self, port: int, udp_port: int, scale: float, failsafe: bool):
        self.port = port
        self.scale = scale
        pyautogui.FAILSAFE = failsafe
        pyautogui.PAUSE = 0
        try:
            pyautogui.FAILSAFE_THRESHOLD = (10, 10)
        except Exception:
            pass
        self.mouse = MouseController(scale)
        self.executor = CommandExecutor(self.mouse)
        self.discovery = DiscoveryServer(udp_port)
        self.sessions: "list[ClientSession]" = []
        self.tcp = None
        self.alive = True

    def run(self) -> None:
        self.executor.start()
        self.discovery.start()

        self.tcp = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.tcp.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            self.tcp.bind(("0.0.0.0", self.port))
        except OSError as e:
            log("Не удалось занять TCP-порт %d: %s" % (self.port, e))
            sys.exit(1)
        self.tcp.listen(4)
        w, h = pyautogui.size()
        log("Сервер запущен. TCP :%d | UDP :%d | экран %dx%d | масштаб %.2f"
            % (self.port, self.discovery.udp_port, w, h, self.scale))
        log("Управление курсором доступно со смартфона в той же Wi-Fi сети.")
        log("Ctrl+C — остановка сервера.")

        try:
            while self.alive:
                conn, addr = self.tcp.accept()
                if not is_local(addr[0]):
                    log("Отклонено внешнее подключение: %s" % addr[0])
                    conn.close()
                    continue
                # новый клиент вытесняет предыдущих (один телефон = одно управление)
                for s in list(self.sessions):
                    s.close()
                    self.sessions.remove(s)
                sess = ClientSession(conn, addr, self.executor)
                self.sessions.append(sess)
                sess.start()
        except OSError:
            pass
        finally:
            self.shutdown()

    def shutdown(self) -> None:
        log("Остановка сервера…")
        self.alive = False
        for s in self.sessions:
            s.close()
        self.discovery.stop()
        self.executor.stop()
        self.mouse.release_all()
        if self.tcp:
            try:
                self.tcp.close()
            except OSError:
                pass


def main() -> None:
    ap = argparse.ArgumentParser(description="TouchCursor server for Windows 10")
    ap.add_argument("--port", type=int, default=DEFAULT_PORT, help="TCP-порт (8721)")
    ap.add_argument("--udp-port", type=int, default=DEFAULT_UDP_PORT,
                    help="UDP-порт автопоиска (8722)")
    ap.add_argument("--scale", type=float, default=1.0,
                    help="множитель чувствительности (например 1.5)")
    ap.add_argument("--failsafe", action="store_true",
                    help="включить FAIL-SAFE pyautogui: курсор в угол экрана → остановка")
    args = ap.parse_args()

    if sys.platform.startswith("win"):
        # стабильнее на высоких DPI
        try:
            import ctypes
            ctypes.windll.shcore.SetProcessDpiAwareness(2)
        except Exception:
            pass

    srv = TouchCursorServer(args.port, args.udp_port, args.scale, args.failsafe)
    try:
        srv.run()
    except KeyboardInterrupt:
        srv.shutdown()


if __name__ == "__main__":
    main()
