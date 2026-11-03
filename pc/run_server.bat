@echo off
rem ============================================================
rem  Запуск сервера TouchCursor на Windows 10
rem  Требуется Python 3.8+ (https://python.org, галочка "Add to PATH")
rem ============================================================

where python >nul 2>nul
if errorlevel 1 (
    echo [ОШИБКА] Python не найден. Установите с python.org и включите "Add Python to PATH".
    pause
    exit /b 1
)

rem Установка зависимостей (только при первом запуске)
python -c "import pyautogui" 2>nul
if errorlevel 1 (
    echo Установка pyautogui...
    python -m pip install --upgrade pyautogui
)

rem Разрешаем входящие подключения в брандмауэре (требуется права администратора;
rem если не получится — Windows сама спросит при первом запуске сервера)
netsh advfirewall firewall show rule name="TouchCursor TCP" >nul 2>nul
if errorlevel 1 (
    netsh advfirewall firewall add rule name="TouchCursor TCP" dir=in action=allow protocol=TCP localport=8721 >nul 2>nul
    netsh advfirewall firewall add rule name="TouchCursor UDP" dir=in action=allow protocol=UDP localport=8722 >nul 2>nul
)

echo Запуск сервера TouchCursor (Ctrl+C — остановка)...
python "%~dp0touchcursor_server.py" %*
pause
