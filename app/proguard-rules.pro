# ProGuard / R8 Optimization Rules (R8 Full Mode & Obtainium / Neo-Store Standard)
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn io.ktor.**
-dontwarn kotlinx.coroutines.**
-dontwarn io.sentry.**
-dontwarn com.chuckerteam.chucker.**

# Conserver intégralement toutes les classes applicatives, ViewModels et modèles
-keep class fr.installapk.app.** { *; }
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keep class * extends android.app.Application { *; }
-keep class * extends android.app.Activity { *; }
