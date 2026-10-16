# ORMLite reads @DatabaseTable/@DatabaseField by reflection and maps columns to field names, so the
# models (and ORMLite itself) must keep their names
-keepattributes *Annotation*
-keep class com.j256.ormlite.** { *; }
-keep class ca.fuwafuwa.gaku.Database.** { *; }

# ONNX Runtime's native code finds its Java classes and methods by name (JNI). The rules in its AAR only
# cover its telemetry classes, so without this the first manga-ocr read aborts the app
-keep class ai.onnxruntime.** { *; }

# ML Kit ships its own consumer rules in its AAR.

# Release builds carry no debug logging: these calls are removed, arguments and all
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

# ORMLite looks for JPA annotations and slf4j at runtime and does without them; neither is on the classpath
-dontwarn javax.persistence.**
-dontwarn org.slf4j.**
