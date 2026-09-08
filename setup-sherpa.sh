#!/bin/bash
# ============================================================
# Sherpa-ONNX Setup Script for Vyze
# Downloads JNI libraries + TTS models (Kokoro + Meta MMS)
# ============================================================

set -e

SHERPA_VERSION="v1.13.7"
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$PROJECT_DIR/app"
JNI_DIR="$APP_DIR/src/main/jniLibs/arm64-v8a"
ASSETS_DIR="$APP_DIR/src/main/assets"

echo "============================================"
echo "  Sherpa-ONNX Setup for Vyze"
echo "  Version: $SHERPA_VERSION"
echo "============================================"
echo ""

# ── Step 1: Create directories ────────────────────────────────
echo "[1/5] Creating directories..."
mkdir -p "$JNI_DIR"
mkdir -p "$ASSETS_DIR/kokoro"
mkdir -p "$ASSETS_DIR/mms"
echo "  ✓ Directories created"

# ── Step 2: Download JNI libraries ───────────────────────────
echo ""
echo "[2/5] Downloading Sherpa-ONNX JNI libraries..."
cd /tmp

if [ ! -f "sherpa-onnx-${SHERPA_VERSION}-android.tar.bz2" ]; then
    wget -q "https://github.com/k2-fsa/sherpa-onnx/releases/download/${SHERPA_VERSION}/sherpa-onnx-${SHERPA_VERSION}-android.tar.bz2"
fi

echo "  Extracting..."
tar xjf "sherpa-onnx-${SHERPA_VERSION}-android.tar.bz2"

echo "  Copying arm64-v8a libraries..."
cp -v jniLibs/arm64-v8a/libonnxruntime.so "$JNI_DIR/"
cp -v jniLibs/arm64-v8a/libsherpa-onnx-jni.so "$JNI_DIR/"
echo "  ✓ JNI libraries installed"

# ── Step 3: Download Kokoro TTS model ────────────────────────
echo ""
echo "[3/5] Downloading Kokoro TTS model (EN/ZH, ~100MB)..."
cd /tmp

if [ ! -f "kokoro-model.tar.bz2" ]; then
    # Kokoro-82M model for sherpa-onnx
    wget -q "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-kokoro-multi-lang-v1.1.tar.bz2" \
        -O kokoro-model.tar.bz2 || {
        echo "  ⚠ Kokoro download failed — you may need to download manually"
        echo "  Visit: https://huggingface.co/hexgrad/Kokoro-82M"
    }
fi

if [ -f "kokoro-model.tar.bz2" ]; then
    tar xjf kokoro-model.tar.bz2
    # Copy relevant files to assets
    find . -name "*.onnx" -path "*kokoro*" -exec cp {} "$ASSETS_DIR/kokoro/model.onnx" \;
    find . -name "tokens.txt" -path "*kokoro*" -exec cp {} "$ASSETS_DIR/kokoro/tokens.txt" \;
    echo "  ✓ Kokoro model installed"
else
    echo "  ⚠ Kokoro model not installed — download manually to $ASSETS_DIR/kokoro/"
fi

# ── Step 4: Download Meta MMS model ──────────────────────────
echo ""
echo "[4/5] Downloading Meta MMS TTS model (Malay, ~114MB)..."
cd /tmp

if [ ! -f "mms-model.tar.bz2" ]; then
    wget -q "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-mms-tts-zlm.tar.bz2" \
        -O mms-model.tar.bz2 || {
        echo "  ⚠ MMS download failed — converting from HuggingFace..."
        # Fallback: direct download from HuggingFace
        wget -q "https://huggingface.co/willwade/mms-tts-multilingual-models-onnx/resolve/main/zlm/model.onnx" \
            -O "$ASSETS_DIR/mms/model.onnx"
        wget -q "https://huggingface.co/willwade/mms-tts-multilingual-models-onnx/resolve/main/zlm/tokens.txt" \
            -O "$ASSETS_DIR/mms/tokens.txt"
    }
fi

if [ -f "mms-model.tar.bz2" ]; then
    tar xjf mms-model.tar.bz2
    find . -name "*.onnx" -path "*mms*" -exec cp {} "$ASSETS_DIR/mms/model.onnx" \;
    find . -name "tokens.txt" -path "*mms*" -exec cp {} "$ASSETS_DIR/mms/tokens.txt" \;
    echo "  ✓ MMS model installed"
else
    echo "  ⚠ MMS model not installed — download manually to $ASSETS_DIR/mms/"
fi

# ── Step 5: Verify ───────────────────────────────────────────
echo ""
echo "[5/5] Verifying installation..."
echo ""

check_file() {
    if [ -f "$1" ]; then
        size=$(du -h "$1" | cut -f1)
        echo "  ✓ $2: $size"
    else
        echo "  ✗ $2: MISSING"
    fi
}

check_file "$JNI_DIR/libonnxruntime.so" "JNI: libonnxruntime.so"
check_file "$JNI_DIR/libsherpa-onnx-jni.so" "JNI: libsherpa-onnx-jni.so"
check_file "$ASSETS_DIR/kokoro/model.onnx" "Model: Kokoro (EN/ZH)"
check_file "$ASSETS_DIR/kokoro/tokens.txt" "Tokens: Kokoro"
check_file "$ASSETS_DIR/mms/model.onnx" "Model: MMS (Malay)"
check_file "$ASSETS_DIR/mms/tokens.txt" "Tokens: MMS"

echo ""
echo "============================================"
echo "  Setup complete!"
echo ""
echo "  JNI libs: $JNI_DIR"
echo "  Models:   $ASSETS_DIR"
echo ""
echo "  Next: Build the APK with ./gradlew assembleDebug"
echo "============================================"
