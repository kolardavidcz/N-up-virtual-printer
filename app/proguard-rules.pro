# Keep PDFBox classes required for vector PDF generation
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-keep class org.apache.fontbox.** { *; }
-dontwarn org.apache.fontbox.**

# Keep PrintService and JavascriptInterface entry points
-keep class com.example.twoupprint.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
