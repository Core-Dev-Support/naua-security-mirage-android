# ===================================================================
# NAUA Security Mirage - ProGuard & R8 Optimization & Obfuscation Rules
# ===================================================================

# General Optimization & Obfuscation
-dontusemixedcaseclassnames

# Flatten obfuscated package hierarchy to obscure project layout
-repackageclasses 'com.naua_security_mirage.app.o'
-allowaccessmodification

# Preserve StackTrace information for Firebase Crashlytics
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod

# Strip verbose and debug logs in release build
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# ===================================================================
# Android Core & Custom Views
# ===================================================================
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.preference.Preference

-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public void set*(...);
}

-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}

-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# ===================================================================
# JNI & Go Native Bindings (libgojni / libXray) - DO NOT OBFUSCATE!
# ===================================================================
-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class go.** { *; }
-keep interface go.** { *; }
-keepclassmembers class go.** { *; }

-keep class libXray.** { *; }
-keep interface libXray.** { *; }
-keepclassmembers class libXray.** { *; }

# ===================================================================
# RuStore In-App Update SDK
# ===================================================================
-keep class ru.rustore.sdk.appupdate.** { *; }
-keep interface ru.rustore.sdk.appupdate.** { *; }
-dontwarn ru.rustore.sdk.**


# ===================================================================
# JSON Serialization (Gson & Data Models)
# ===================================================================
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

-keep class com.google.gson.** { *; }
-keep class com.naua_security_mirage.app.data.model.** { *; }
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ===================================================================
# AppShield Anti-Tamper & Anti-Debug
# ===================================================================
-keepclassmembers class com.naua_security_mirage.app.util.AppShield {
    public static void checkIntegrity(android.content.Context);
}
