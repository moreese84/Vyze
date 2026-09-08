@echo off
title Download MMS Malay Model
color 0A

echo.
echo ============================================
echo   Downloading MMS Malay TTS Model
echo ============================================
echo.

if not exist "app\src\main\assets\mms" mkdir "app\src\main\assets\mms"

echo Downloading model.onnx (~114MB)...
curl -L -o "app\src\main\assets\mms\model.onnx" "https://huggingface.co/willwade/mms-tts-multilingual-models-onnx/resolve/main/zlm/model.onnx"
if %errorlevel% neq 0 (
    echo ERROR: Download failed
    pause
    exit /b 1
)

echo Downloading tokens.txt...
curl -L -o "app\src\main\assets\mms\tokens.txt" "https://huggingface.co/willwade/mms-tts-multilingual-models-onnx/resolve/main/zlm/tokens.txt"

echo.
echo ============================================
if exist "app\src\main\assets\mms\model.onnx" (
    echo   OK - MMS Malay model installed!
) else (
    echo   FAILED - Model not downloaded
)
echo ============================================
echo.
pause
