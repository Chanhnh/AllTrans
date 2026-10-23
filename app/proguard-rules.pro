# AllTrans is built without R8/minification. These rules are kept so that a
# future minified build still keeps the modern Xposed entry point discoverable.

-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# Keep AllTrans package and all its classes/members for reflection-based hooks.
-keep class chanhnh.alltrans.** { *; }
-keep interface chanhnh.alltrans.** { *; }

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
