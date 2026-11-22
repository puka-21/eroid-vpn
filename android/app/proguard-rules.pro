# Proguard rules for EROID VPN
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.eroid.vpn.model.** { *; }
