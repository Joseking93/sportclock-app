# org.json and Compose need no extra rules; keep the widget receiver (referenced from the manifest).
-keep class app.sportclock.widget.** { *; }
# Crash reports arrive as GitHub issues, so keep stack traces readable: shrink, but do not rename, and keep line numbers.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
