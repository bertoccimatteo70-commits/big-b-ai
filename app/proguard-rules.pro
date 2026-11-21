# ITAGPT release rules. R8 is enabled for release; these rules protect the
# boundaries that reflection, JNI, Room and service loading cross.

# JNI: names are resolved by the native library, and native methods are called
# from Kotlin. Renaming either side breaks the engine at runtime.
-keep class com.bigbai.llm.NativeLlama { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Room generated implementations and entities.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep class androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# kotlinx.serialization: generated serializers are looked up reflectively.
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class com.bigbai.core.model.** { *; }

# JGit uses reflection on repository backends and service providers.
-keep class org.eclipse.jgit.** { *; }
-dontwarn org.eclipse.jgit.**
-dontwarn org.slf4j.**
-dontwarn javax.activation.**
-dontwarn java.awt.**
-dontwarn javax.**

# PDFBox Android loads fonts and resource providers reflectively.
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**

# SQLCipher native bridge.
-keep class net.zetetic.** { *; }
-dontwarn net.zetetic.**

# ML Kit entry points and its bundled Latin OCR model.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_common.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# Keep annotations used by Compose and lifecycle at runtime.
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
