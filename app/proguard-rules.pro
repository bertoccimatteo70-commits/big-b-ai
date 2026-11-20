# JGit uses reflection on repository backends.
-keep class org.eclipse.jgit.** { *; }
-dontwarn org.eclipse.jgit.**
-dontwarn org.slf4j.**
# PDFBox optional AWT/font classes are absent on Android.
-dontwarn com.tom_roush.pdfbox.**
-dontwarn javax.**
-dontwarn java.awt.**
# Room generated implementations
-keep class * extends androidx.room.RoomDatabase { <init>(); }
