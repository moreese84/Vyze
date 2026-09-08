@echo off
title Vyze Sherpa-ONNX Setup
color 0A

echo.
echo ============================================
echo   Vyze Sherpa-ONNX Setup
echo ============================================
echo.
echo This will download ~234MB of files:
echo   - JNI libraries (speech engine runtime)
echo   - Kokoro model (English/Chinese voice)
echo   - MMS model (Malay voice)
echo.

REM Check if running from correct directory
if not exist "app\build.gradle.kts" (
    echo ERROR: Run this file from the Vyze project root folder!
    echo.
    echo Current directory: %CD%
    echo.
    echo Make sure you see app\build.gradle.kts in this folder.
    pause
    exit /b 1
)

echo Starting download...
echo.

REM Try PowerShell with bypass policy
powershell -NoProfile -ExecutionPolicy Bypass -Command "& '%~dp0setup-sherpa.ps1'"

if %errorlevel% neq 0 (
    echo.
    echo ERROR: PowerShell script failed.
    echo.
    echo Alternative: Open PowerShell manually and run:
    echo   Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
    echo   .\setup-sherpa.ps1
    echo.
)

echo.
echo Press any key to exit...
pause >nul
