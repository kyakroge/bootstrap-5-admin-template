# Keep the legacy JS bridge methods callable from JavaScript.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
