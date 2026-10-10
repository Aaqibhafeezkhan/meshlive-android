# Release builds only (app/build.gradle.kts, buildTypes.release; research catalog P10a, 2026-10-10).
# R8 removes android.util.Log verbose/debug/info calls, and the message strings built only for them,
# so diagnostic text (session, peer and link details) is not written to logcat by release APKs.
# Warnings and errors (Log.w/e/wtf) stay for user diagnostics. Debug builds are not minified and
# never read this file, so every debug log line, including the harness's BENCHMARK lines, is kept.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
