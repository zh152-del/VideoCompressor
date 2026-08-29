@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
set "JAVA_HOME=E://Android//jbr"
set "PATH=%JAVA_HOME%\bin;%PATH%"
cd /d "%~dp0"

set "GRADLE_EXE=gradlew.bat"
if exist "C://Users//Administrator//.workbuddy//binaries//gradle//gradle-8.11.1//bin//gradle.bat" (
  set "GRADLE_EXE=C://Users//Administrator//.workbuddy//binaries//gradle//gradle-8.11.1//bin//gradle.bat"
)

echo [构建] 开始编译 Release APK (使用本地 Gradle) ...
call !GRADLE_EXE! assembleRelease --no-daemon
if errorlevel 1 (
  echo [失败] 构建出错，请查看上方日志
  pause
  exit /b 1
)
echo [完成] 产物位于 app\build\outputs\apk\release\app-release.apk
pause
