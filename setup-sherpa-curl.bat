@echo off
title Vyze Sherpa-ONNX Setup
color 0A

echo.
echo ============================================
echo   Vyze Sherpa-ONNX Setup
echo ============================================
echo.

REM Create directories
echo [1/4] Creating directories...
if not exist "app\src\main\jniLibs\arm64-v8a" mkdir "app\src\main\jniLibs\arm64-v8a"
if not exist "app\src\main\assets\kokoro" mkdir "app\src\main\assets\kokoro"
if not exist "app\src\main\assets\mms" mkdir "app\src\main\assets\mms"
if not exist "_sherpa_temp" mkdir "_sherpa_temp"
echo   OK
echo.

REM Download JNI libraries
echo [2/4] Downloading JNI libraries (~20MB)...
curl -L -o "_sherpa_temp\sherpa-android.tar.bz2" "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.7/sherpa-onnx-v1.13.7-android.tar.bz2"
if %errorlevel% neq 0 (
    echo   ERROR: JNI download failed.
    echo   URL: https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.7/sherpa-onnx-v1.13.7-android.tar.bz2
    pause
    exit /b 1
)
echo   Extracting...
tar xf "_sherpa_temp\sherpa-android.tar.bz2" -C "_sherpa_temp"
copy "_sherpa_temp\jniLibs\arm64-v8a\libonnxruntime.so" "app\src\main\jniLibs\arm64-v8a\" >nul
copy "_sherpa_temp\jniLibs\arm64-v8a\libsherpa-onnx-jni.so" "app\src\main\jniLibs\arm64-v8a\" >nul
echo   OK - JNI libraries installed
echo.

REM Download Kokoro multi-lang model (EN + ZH)
echo [3/4] Downloading Kokoro TTS model (EN/ZH, ~100MB)...
curl -L -o "_sherpa_temp\kokoro.tar.bz2" "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_1.tar.bz2"
if %errorlevel% neq 0 (
    echo   WARNING: Kokoro download failed, trying alternative...
    curl -L -o "_sherpa_temp\kokoro.tar.bz2" "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_0.tar.bz2"
)
if exist "_sherpa_temp\kokoro.tar.bz2" (
    echo   Extracting...
    tar xf "_sherpa_temp\kokoro.tar.bz2" -C "_sherpa_temp"
    REM Find and copy model files
    for /r "_sherpa_temp" %%f in (*.onnx) do (
        echo %%f | findstr /i "kokoro" >nul && copy "%%f" "app\src\main\assets\kokoro\model.onnx" >nul
    )
    copy "_sherpa_temp\kokoro-multi-lang-v1_1\tokens.txt" "app\src\main\assets\kokoro\tokens.txt" >nul 2>&1
    copy "_sherpa_temp\kokoro-multi-lang-v1_0\tokens.txt" "app\src\main\assets\kokoro\tokens.txt" >nul 2>&1
    echo   OK - Kokoro model installed
) else (
    echo   SKIPPED - Kokoro not available
)
echo.

REM Download MMS Malay model
echo [4/4] Downloading MMS Malay model (~114MB)...
curl -L -o "_sherpa_temp\mms-zlm.tar.bz2" "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-mms-zlm.tar.bz2"
if %errorlevel% neq 0 (
    echo   WARNING: MMS download failed, trying alternative...
    curl -L -o "_sherpa_temp\mms-zlm.tar.bz2" "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/mms-tts-zlm.tar.bz2"
)
if exist "_sherpa_temp\mms-zlm.tar.bz2" (
    echo   Extracting...
    tar xf "_sherpa_temp\mms-zlm.tar.bz2" -C "_sherpa_temp"
    for /r "_sherpa_temp" %%f in (*.onnx) do (
        echo %%f | findstr /i "zlm" >nul && copy "%%f" "app\src\main\assets\mms\model.onnx" >nul
    )
    for /r "_sherpa_temp" %%f in (tokens.txt) do (
        echo %%f | findstr /i "zlm" >nul && copy "%%f" "app\src\main\assets\mms\tokens.txt" >nul
    )
    echo   OK - MMS model installed
) else (
    echo   SKIPPED - MMS not available
)
echo.

REM Cleanup
echo Cleaning up temp files...
rmdir /s /q "_sherpa_temp" 2>nul

REM Verify
echo.
echo ============================================
echo   Verification
echo ============================================
echo.
if exist "app\src\main\jniLibs\arm64-v8a\libonnxruntime.so" (echo   OK  JNI: libonnxruntime.so) else (echo   MISSING  JNI: libonnxruntime.so)
if exist "app\src\main\jniLibs\arm64-v8a\libsherpa-onnx-jni.so" (echo   OK  JNI: libsherpa-onnx-jni.so) else (echo   MISSING  JNI: libsherpa-onnx-jni.so)
if exist "app\src\main\assets\kokoro\model.onnx" (echo   OK  Model: Kokoro EN/ZH) else (echo   MISSING  Model: Kokoro)
if exist "app\src\main\assets\mms\model.onnx" (echo   OK  Model: MMS Malay) else (echo   MISSING  Model: MMS Malay)

echo.
echo ============================================
echo   Done! Build with Android Studio now.
echo ============================================
echo.
pause
