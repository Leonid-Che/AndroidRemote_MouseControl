@echo off
rem ============================================================
rem  Запуск собранного сервера TouchCursorServer.exe
rem  Python на этом компьютере НЕ требуется.
rem  Скрипт один раз добавляет правила брандмауэра и запускает EXE.
rem ============================================================
chcp 65001 >nul
setlocal

cd /d "%~dp0"

if not exist "TouchCursorServer\TouchCursorServer.exe" (
    rem если запуск из dist\TouchCursorServer — EXE лежит рядом
    if not exist "TouchCursorServer.exe" (
        echo [ОШИБКА] TouchCursorServer.exe не найден.
        echo Сначала соберите его: build_exe.bat ^(на машине с Python^).
        pause
        exit /b 1
    )
    set "EXE=%~dp0TouchCursorServer.exe"
) else (
    set "EXE=%~dp0TouchCursorServer\TouchCursorServer.exe"
)

rem Правила брандмауэра (нужны права администратора; без них Windows
rem сама спросит при первом запуске — нажмите "Разрешить доступ")
netsh advfirewall firewall show rule name="TouchCursor TCP" >nul 2>nul
if errorlevel 1 (
    netsh advfirewall firewall add rule name="TouchCursor TCP" dir=in action=allow protocol=TCP localport=8721 >nul 2>nul
    netsh advfirewall firewall add rule name="TouchCursor UDP" dir=in action=allow protocol=UDP localport=8722 >nul 2>nul
)

echo Запуск TouchCursorServer.exe (Ctrl+C или закрытие окна — остановка)...
"%EXE%" %*
pause
