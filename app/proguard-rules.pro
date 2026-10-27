# Network responses are parsed with org.json; no reflection-based model mapping.
# Keep the local mapping.txt for diagnosing release crashes; never distribute it.
-renamesourcefileattribute SourceFile
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
