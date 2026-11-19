# Google's legacy Android Auto SDK is reflected on / bound by the Android Auto host.
-keep class com.google.android.apps.auto.sdk.** { *; }
-keep class com.google.android.gms.car.** { *; }
-keep class android.support.car.** { *; }
-dontwarn com.google.android.apps.auto.sdk.**
-dontwarn com.google.android.gms.car.**
-dontwarn android.support.car.**
-keep class app.boophis.car.projection.** { *; }
