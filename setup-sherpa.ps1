# ============================================================
#  Vyze Sherpa-ONNX Setup (Windows)
#  Downloads JNI libraries + TTS models automatically
# ============================================================

$ErrorActionPreference = "Stop"

$PROJECT_DIR = Get-Location
$APP_DIR = "$PROJECT_DIR\app"
$JNI_DIR = "$APP_DIR\src\main\jniLibs\arm64-v8a"
$ASSETS_DIR = "$APP_DIR\src\main\assets"
$TEMP_DIR = "$PROJECT_DIR\_sherpa_download"

Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  Sherpa-ONNX Setup for Vyze" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""

# ── Step 1: Create directories ────────────────────────────────
Write-Host "[1/4] Creating directories..." -ForegroundColor Yellow
New-Item -ItemType Directory -Force -Path $JNI_DIR | Out-Null
New-Item -ItemType Directory -Force -Path "$ASSETS_DIR\kokoro" | Out-Null
New-Item -ItemType Directory -Force -Path "$ASSETS_DIR\mms" | Out-Null
New-Item -ItemType Directory -Force -Path $TEMP_DIR | Out-Null
Write-Host "  OK" -ForegroundColor Green

# ── Step 2: Download JNI libraries ───────────────────────────
Write-Host ""
Write-Host "[2/4] Downloading Sherpa-ONNX JNI libraries (~20MB)..." -ForegroundColor Yellow

$JNI_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.7/sherpa-onnx-v1.13.7-android.tar.bz2"
$JNI_ARCHIVE = "$TEMP_DIR\sherpa-onnx-android.tar.bz2"

if (-not (Test-Path $JNI_ARCHIVE)) {
    Write-Host "  Downloading from GitHub..."
    Invoke-WebRequest -Uri $JNI_URL -OutFile $JNI_ARCHIVE -UseBasicParsing
}

Write-Host "  Extracting..."
# Use tar (built into Windows 10+)
tar xf $JNI_ARCHIVE -C $TEMP_DIR

# Copy JNI libs
Copy-Item "$TEMP_DIR\jniLibs\arm64-v8a\libonnxruntime.so" $JNI_DIR -Force
Copy-Item "$TEMP_DIR\jniLibs\arm64-v8a\libsherpa-onnx-jni.so" $JNI_DIR -Force
Write-Host "  OK - JNI libraries installed" -ForegroundColor Green

# ── Step 3: Download Kokoro TTS model (EN/ZH) ───────────────
Write-Host ""
Write-Host "[3/4] Downloading Kokoro TTS model (EN/ZH, ~100MB)..." -ForegroundColor Yellow

$KOKORO_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-kokoro-multi-lang-v1.1.tar.bz2"
$KOKORO_ARCHIVE = "$TEMP_DIR\kokoro-model.tar.bz2"

if (-not (Test-Path $KOKORO_ARCHIVE)) {
    Write-Host "  Downloading from GitHub..."
    try {
        Invoke-WebRequest -Uri $KOKORO_URL -OutFile $KOKORO_ARCHIVE -UseBasicParsing
    } catch {
        Write-Host "  WARNING: Kokoro download failed. Trying alternative..." -ForegroundColor Yellow
        # Alternative: try direct HuggingFace download
        $KOKORO_URL2 = "https://huggingface.co/hexgrad/Kokoro-82M/resolve/main/kokoro-v1.1.onnx"
        Invoke-WebRequest -Uri $KOKORO_URL2 -OutFile "$ASSETS_DIR\kokoro\model.onnx" -UseBasicParsing
        $KOKORO_TOKENS_URL = "https://huggingface.co/hexgrad/Kokoro-82M/resolve/main/tokens.txt"
        Invoke-WebRequest -Uri $KOKORO_TOKENS_URL -OutFile "$ASSETS_DIR\kokoro\tokens.txt" -UseBasicParsing
        Write-Host "  OK - Kokoro model installed (alternative source)" -ForegroundColor Green
        continue
    }
}

Write-Host "  Extracting..."
tar xf $KOKORO_ARCHIVE -C $TEMP_DIR

# Find and copy model files
$kokoroModel = Get-ChildItem -Path $TEMP_DIR -Recurse -Filter "*.onnx" | Where-Object { $_.FullName -like "*kokoro*" } | Select-Object -First 1
$kokoroTokens = Get-ChildItem -Path $TEMP_DIR -Recurse -Filter "tokens.txt" | Where-Object { $_.FullName -like "*kokoro*" } | Select-Object -First 1

if ($kokoroModel) { Copy-Item $kokoroModel.FullName "$ASSETS_DIR\kokoro\model.onnx" -Force }
if ($kokoroTokens) { Copy-Item $kokoroTokens.FullName "$ASSETS_DIR\kokoro\tokens.txt" -Force }
Write-Host "  OK - Kokoro model installed" -ForegroundColor Green

# ── Step 4: Download Meta MMS model (Malay) ─────────────────
Write-Host ""
Write-Host "[4/4] Downloading Meta MMS TTS model (Malay, ~114MB)..." -ForegroundColor Yellow

$MMS_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-mms-tts-zlm.tar.bz2"
$MMS_ARCHIVE = "$TEMP_DIR\mms-model.tar.bz2"

if (-not (Test-Path $MMS_ARCHIVE)) {
    Write-Host "  Downloading from GitHub..."
    try {
        Invoke-WebRequest -Uri $MMS_URL -OutFile $MMS_ARCHIVE -UseBasicParsing
    } catch {
        Write-Host "  WARNING: MMS download failed. Trying HuggingFace..." -ForegroundColor Yellow
        $MMS_URL2 = "https://huggingface.co/willwade/mms-tts-multilingual-models-onnx/resolve/main/zlm/model.onnx"
        Invoke-WebRequest -Uri $MMS_URL2 -OutFile "$ASSETS_DIR\mms\model.onnx" -UseBasicParsing
        $MMS_TOKENS_URL = "https://huggingface.co/willwade/mms-tts-multilingual-models-onnx/resolve/main/zlm/tokens.txt"
        Invoke-WebRequest -Uri $MMS_TOKENS_URL -OutFile "$ASSETS_DIR\mms\tokens.txt" -UseBasicParsing
        Write-Host "  OK - MMS model installed (alternative source)" -ForegroundColor Green
        continue
    }
}

Write-Host "  Extracting..."
tar xf $MMS_ARCHIVE -C $TEMP_DIR

$mmsModel = Get-ChildItem -Path $TEMP_DIR -Recurse -Filter "*.onnx" | Where-Object { $_.FullName -like "*mms*" -or $_.FullName -like "*zlm*" } | Select-Object -First 1
$mmsTokens = Get-ChildItem -Path $TEMP_DIR -Recurse -Filter "tokens.txt" | Where-Object { $_.FullName -like "*mms*" -or $_.FullName -like "*zlm*" } | Select-Object -First 1

if ($mmsModel) { Copy-Item $mmsModel.FullName "$ASSETS_DIR\mms\model.onnx" -Force }
if ($mmsTokens) { Copy-Item $mmsTokens.FullName "$ASSETS_DIR\mms\tokens.txt" -Force }
Write-Host "  OK - MMS model installed" -ForegroundColor Green

# ── Verify ───────────────────────────────────────────────────
Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  Verification" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""

function Check-File($Path, $Name) {
    if (Test-Path $Path) {
        $size = (Get-Item $Path).Length / 1MB
        Write-Host "  OK  $Name ([math]::Round($size, 1) MB)" -ForegroundColor Green
    } else {
        Write-Host "  MISSING  $Name" -ForegroundColor Red
    }
}

Check-File "$JNI_DIR\libonnxruntime.so" "JNI: libonnxruntime.so"
Check-File "$JNI_DIR\libsherpa-onnx-jni.so" "JNI: libsherpa-onnx-jni.so"
Check-File "$ASSETS_DIR\kokoro\model.onnx" "Model: Kokoro (EN/ZH)"
Check-File "$ASSETS_DIR\kokoro\tokens.txt" "Tokens: Kokoro"
Check-File "$ASSETS_DIR\mms\model.onnx" "Model: MMS (Malay)"
Check-File "$ASSETS_DIR\mms\tokens.txt" "Tokens: MMS"

# Cleanup temp files
Write-Host ""
Write-Host "Cleaning up temp files..."
Remove-Item -Recurse -Force $TEMP_DIR -ErrorAction SilentlyContinue

Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  Setup complete!" -ForegroundColor Green
Write-Host ""
Write-Host "  JNI libs:  $JNI_DIR"
Write-Host "  Models:    $ASSETS_DIR"
Write-Host ""
Write-Host "  Next step: Build with Android Studio"
Write-Host "             or run: .\gradlew.bat assembleDebug"
Write-Host "============================================" -ForegroundColor Cyan
