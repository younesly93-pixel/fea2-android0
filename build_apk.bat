@echo off
chcp 65001 >nul
cd /d "%~dp0"
rem Builds the signed APK. Requires Android Studio (or the Android SDK) installed on this computer.
if "%ANDROID_HOME%"=="" if exist "%LOCALAPPDATA%\Android\Sdk" set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
if "%ANDROID_HOME%"=="" (
  echo Android SDK not found. Install Android Studio first: https://developer.android.com/studio
  pause
  exit /b 1
)
echo sdk.dir=%ANDROID_HOME:\=\\%> local.properties
if "%JAVA_HOME%"=="" if exist "%ProgramFiles%\Android\Android Studio\jbr" set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"
call gradlew.bat --no-daemon assembleRelease
if errorlevel 1 (
  echo.
  echo Build failed. See the messages above.
  pause
  exit /b 1
)
copy /y "app\build\outputs\apk\release\app-release.apk" "FEA-System.apk" >nul
echo.
echo Done: FEA-System.apk is next to this file. Copy it to the phones and install it.
pause
