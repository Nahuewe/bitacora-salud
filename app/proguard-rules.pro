-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*,Exceptions

-keep class com.samsung.android.sdk.health.data.** { *; }
-keep interface com.samsung.android.sdk.health.data.** { *; }
-dontwarn com.samsung.android.sdk.health.data.**

-keep class kotlinx.parcelize.** { *; }
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**
-dontwarn androidx.databinding.**
