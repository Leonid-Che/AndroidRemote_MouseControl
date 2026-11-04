@echo off
rem ============================================================
rem  Сборка TouchCursorServer.exe (PyInstaller, one-dir режим)
rem  Требуется: Windows 10 + Python 3.8 - 3.12 (один раз для сборки).
rem  Bluetooth-поддержка (pybluez) требует Python 3.8 - 3.11.
rem  Результат:  dist\TouchCursorServer\TouchCursorServer.exe
rem  Готовую папку dist\TouchCursorServer можно копировать на любые
rem  ПК без Python и запускать двойным кликом (run_exe.bat).
rem ============================================================
chcp 65001 >nul
setlocal EnableExtensions EnableDelayedExpansion

cd /d "%~dp0"

rem ---------- [0] Поиск и проверка версии Python ----------
set "PY="
where python >nul 2>nul && set "PY=python"
if not defined PY where py >nul 2>nul && set "PY=py -3"
if not defined PY (
    echo [ОШИБКА] Python не найден в PATH.
    echo          Установите Python 3.11 с https://www.python.org/downloads/
    echo          и при установке ОБЯЗАТЕЛЬНО отметьте галку "Add Python to PATH".
    pause
    exit /b 1
)

echo [0/4] Проверка версии Python...
for /f "usebackq delims=" %%V in (`"%PY%" -c "import sys;print('%%d.%%d'%%(sys.version_info.major,sys.version_info.minor))" 2^>nul`) do set "PYVER=%%V"
if not defined PYVER (
    echo [ОШИБКА] Не удалось определить версию Python ^(команда "%PY%" не работает^).
    echo          Переустановите Python или добавьте его полный путь в переменную PATH.
    pause
    exit /b 1
)
echo       Обнаружен Python !PYVER.

rem Разбираем мажор/минор версии в числа
set "VMajor="
set "VMinor="
for /f "tokens=1,2 delims=." %%A in ("!PYVER!") do (
    set "VMajor=%%A"
    set "VMinor=%%B"
)

if not "!VMajor!"=="3" (
    echo [ОШИБКА] Найден Python !PYVER!, а требуется Python 3.x.
    echo          Установите Python 3.11 ^(или любой 3.8-3.12^) с python.org.
    pause
    exit /b 1
)
if !VMinor! LSS 8 (
    echo [ОШИБКА] Python !PYVER! слишком старый ^(нужен 3.8 или новее^).
    echo          Установите Python 3.11 с https://www.python.org/downloads/
    pause
    exit /b 1
)
if !VMinor! GTR 12 (
    echo [ОШИБКА] Python !PYVER! слишком новый - PyInstaller и pybluez пока
    echo          не поддерживают эту версию.
    echo          Установите Python 3.11: https://www.python.org/downloads/release/python-3119/
    pause
    exit /b 1
)
if !VMinor! GTR 11 (
    echo       ! ВНИМАНИЕ: на Python !PYVER! библиотека pybluez скорее всего
    echo         не установится ^(нет готовых бинарных пакетов^), поэтому EXE
    echo         будет собран БЕЗ Bluetooth - только Wi-Fi. Если нужен
    echo         Bluetooth, используйте Python 3.8-3.11. Продолжаем сборку...
)

rem ---------- [1] Зависимости ----------
echo [1/4] Проверка зависимостей...
"%PY%" -c "import pyautogui" 2>nul
if errorlevel 1 (
    echo       Установка pyautogui...
    "%PY%" -m pip install --upgrade pip >nul 2>nul
    "%PY%" -m pip install --upgrade pyautogui || goto :fail_pip
)

rem Bluetooth-поддержка опциональна: pybluez имеет бинарные пакеты только
rem для Python 3.8-3.11; для более новых версий потребовались бы Visual C++
rem Build Tools. Если установка не удалась - EXE соберётся без Bluetooth.
set "BT_OK=0"
"%PY%" -c "import bluetooth" 2>nul
if errorlevel 1 (
    echo       Установка pybluez ^(Bluetooth, опционально^)...
    "%PY%" -m pip install pybluez >bt_install.log 2>&1
    if errorlevel 1 goto :bt_fail
    "%PY%" -c "import bluetooth" 2>nul
    if errorlevel 1 goto :bt_fail
    set "BT_OK=1"
) else (
    set "BT_OK=1"
)
goto :bt_done

:bt_fail
echo.
echo ============================================================
echo  Bluetooth НЕ будет включён в EXE - сборка продолжится только
echo  с Wi-Fi. Возможные причины и решения:
echo.
echo   1. Версия Python !PYVER! не поддерживается pybluez.
echo      Решение: установите Python 3.11
echo      https://www.python.org/downloads/release/python-3119/
echo      и запустите этот скрипт заново из-под него.
echo.
echo   2. Нет готового бинарного пакета для вашей платформы
echo      ^(например, Windows on ARM^).
echo      Решение: установите Visual Studio Build Tools ^(галлка
echo      "Desktop development with C++"^) и повторите вручную:
echo      "%PY%" -m pip install cython wheel pybluez
echo.
echo   3. Корпоративный прокси или антивирус блокируют pip.
echo      Полный текст ошибки установки - в файле bt_install.log
echo      ^(рядом с этим скриптом^).
echo.
echo  Если Bluetooth не нужен - просто дождитесь окончания сборки.
echo ============================================================
echo.

:bt_done
"%PY%" -c "import PyInstaller" 2>nul
if errorlevel 1 (
    echo       Установка PyInstaller...
    "%PY%" -m pip install --upgrade pyinstaller || goto :fail_pip
)

rem ---------- [2] Очистка ----------
echo [2/4] Очистка предыдущей сборки...
if exist build rd /s /q build
if exist dist rd /s /q dist

rem ---------- [3] Сборка ----------
echo [3/4] Сборка EXE ^(может занять 1-3 минуты^)...
"%PY%" -m PyInstaller --noconfirm touchcursor_server.spec || goto :fail

rem ---------- [4] Копирование вспомогательных файлов ----------
echo [4/4] Копирование вспомогательных файлов в dist...
copy /y run_exe.bat dist\TouchCursorServer\ >nul
copy /y touchcursor.ini dist\TouchCursorServer\ >nul
if exist bt_pair_server.py copy /y bt_pair_server.py dist\TouchCursorServer\ >nul
if exist run_bt_pair.bat copy /y run_bt_pair.bat dist\TouchCursorServer\ >nul

echo.
echo ============================================================
echo  ГОТОВО! Результат: %~dp0dist\TouchCursorServer\
if "!BT_OK!"=="1" (
    echo  Поддерживаемые транспорты: Wi-Fi + Bluetooth
) else (
    echo  Поддерживаемые транспорты: только Wi-Fi ^(Bluetooth недоступен -
    echo  см. подсказки выше; пересоберите под Python 3.8-3.11, если нужен BT^)
)
echo  Запуск:  двойной клик по TouchCursorServer.exe
echo  или:     run_exe.bat ^(он же добавит правила брандмауэра^)
echo  Скопируйте папку TouchCursorServer на целевой ПК целиком.
echo ============================================================
pause
exit /b 0

:fail_pip
echo.
echo [ОШИБКА] Не удалось установить зависимости через pip.
echo          Проверьте интернет-соединение и права ^(попробуйте запустить
echo          скрипт от администратора^). Текст ошибки выше.
pause
exit /b 1

:fail
echo.
echo [ОШИБКА] Сборка не удалась. Текст ошибки выше.
pause
exit /b 1
