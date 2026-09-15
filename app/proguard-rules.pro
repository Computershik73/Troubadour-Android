# Мост в веб-вид зовётся из JavaScript по имени — рефлексией, которой
# ужимальщик не видит. Без этих строк PO-токен и расшифровка `n` молча
# перестают работать в релизе: страница зовёт метод, которого уже нет.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ExoPlayer сам ищет расширения по имени класса.
-dontwarn com.google.android.exoplayer2.**
-keep class com.google.android.exoplayer2.** { *; }

# OkHttp тянет за собой необязательные Conscrypt и Animal Sniffer.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.codehaus.mojo.animal_sniffer.*

# Имена наших классов нужны в журнале: строки вида
# «[YouTube/Плеер] …» пишутся вручную, но следы падений — нет.
-keepattributes SourceFile,LineNumberTable
