# Native code calls back into these by name.
-keep class com.zellahq.whisper.** { *; }
-keep class org.tensorflow.lite.** { *; }
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }
# Media3 finds these by reflection.
-keep class androidx.media3.decoder.** { *; }
-dontwarn org.tensorflow.lite.**
-dontwarn com.google.auto.value.**
# Line numbers, so a crash a person reports can be read. Nothing else is kept for this.
-keepattributes SourceFile,LineNumberTable
