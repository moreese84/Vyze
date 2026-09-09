# Vyze ProGuard / R8 Rules
# ─────────────────────────────────────────────────────────────────────────────

# ── Sherpa-ONNX JNI Bindings ────────────────────────────────────────────────
# The native libsherpa-onnx-jni.so accesses Java fields via JNI GetFieldID /
# GetObjectField. ProGuard must not rename or remove any class, field, or
# method in the com.k2fsa.sherpa.onnx package.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

# ── ML Kit Text Recognition ──────────────────────────────────────────────────
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# ── MediaPipe Vision / Tasks ─────────────────────────────────────────────────
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# ── TensorFlow Lite ──────────────────────────────────────────────────────────
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# ── CameraX ──────────────────────────────────────────────────────────────────
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# ── Google Play Services (used by ML Kit) ────────────────────────────────────
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# ── Preserve line numbers for crash reports ───────────────────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── General Android ──────────────────────────────────────────────────────────
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses,EnclosingMethod

# ── Kotlin coroutines ────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ── Data binding / View binding ──────────────────────────────────────────────
-keep class com.vyze.app.databinding.** { *; }
-keep class com.vyze.app.databinding.* { *; }

# ── Annotation processing (AutoValue / JavaPoet) ────────────────────────────
-dontwarn javax.lang.model.SourceVersion
-dontwarn javax.lang.model.element.Element
-dontwarn javax.lang.model.element.ElementKind
-dontwarn javax.lang.model.element.Modifier
-dontwarn javax.lang.model.type.TypeMirror
-dontwarn javax.lang.model.type.TypeVisitor
-dontwarn javax.lang.model.util.SimpleTypeVisitor8