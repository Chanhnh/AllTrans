# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /home/akhil/android-sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any project specific keep options here:

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Xposed Framework rules
-keep class de.robv.android.xposed.** { *; }
-keep interface de.robv.android.xposed.** { *; }

# Keep AllTrans package and all its classes/members
# This ensures that Xposed can find the entry point and reflection works
-keep class chanhnh.alltrans.** { *; }
-keep interface chanhnh.alltrans.** { *; }

# Also explicitly keep types that implement Xposed interfaces
-keep class * implements de.robv.android.xposed.IXposedMod { *; }
-keep class * implements de.robv.android.xposed.IXposedHookLoadPackage { *; }
-keep class * implements de.robv.android.xposed.IXposedHookZygoteInit { *; }
-keep class * implements de.robv.android.xposed.IXposedHookInitPackageResources { *; }

# ML Kit rules
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_translate.** { *; }
-keep class com.google.android.gms.internal.mlkit_language_id.** { *; }
# Keep a generic class that is used by ML Kit:
-keep class com.google.android.gms.common.internal.safeparcel.SafeParcelable { *; }

# OkHttp rules
-dontwarn okio.**
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keepattributes Signature
-keepattributes InnerClasses

# Gson rules
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes InnerClasses
# For keeping field names for Gson serialization/deserialization
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# General Android rules (for reflection)
-keepattributes EnclosingMethod, InnerClasses, Signature
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
