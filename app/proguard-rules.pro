# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ONNX Runtime — all classes are called from native JNI code and must be kept intact
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { *; }

# TensorFlow Lite — keep interpreter and support library classes used at runtime
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.support.** { *; }

# TFLite Support library references com.google.auto.value.AutoValue (annotation
# processor compile-time only) in TensorAudio — suppress the missing-class error.
-dontwarn com.google.auto.value.AutoValue
-dontwarn com.google.auto.value.AutoValue$Builder