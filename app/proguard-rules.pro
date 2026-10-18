# Rafeeq release ProGuard/R8 rules.
#
# Release builds currently ship with minification disabled (isMinifyEnabled = false), so this
# file is a safety net: it keeps the rules that would be required the moment shrinking is turned
# on, instead of failing the release build because the file is missing.

# Retrofit / OkHttp: keep the HTTP interfaces and their generic signatures.
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response

# Gson deserialises our DTOs reflectively, so their fields must keep their names.
-keepattributes Signature
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# The skill/notification result models are read reflectively by Gson in the AI layer.
-keep class com.rafeeq.assistant.ai.** { *; }
