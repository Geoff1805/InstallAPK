# ProGuard / R8 Optimization Rules (R8 Full Mode)
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn io.ktor.**
-dontwarn kotlinx.coroutines.**
-dontwarn io.sentry.**
-dontwarn com.chuckerteam.chucker.**
-keep class fr.installapk.app.model.** { *; }
-keep class fr.installapk.app.data.** { *; }
