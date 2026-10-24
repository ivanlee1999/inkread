# Rhino 1.9.1 Context creates this interpreter by its class name and no-arg constructor.
-keep class org.mozilla.javascript.Interpreter { public <init>(); }
# Rhino discovers its RegExp implementation through META-INF/services/RegExpLoader.
-keep class org.mozilla.javascript.regexp.RegExpLoaderImpl { public <init>(); }

# AndroidJUnitRunner lives in the separate test APK but resolves this shared class
# from the minified target APK. Preserve its public static tracing ABI: target-only
# R8 otherwise inlines/removes methods still called by the instrumentation runner.
# Keep only this facade, not AndroidX generally or its tracing implementation classes.
-keep class androidx.tracing.Trace {
    public static void beginSection(java.lang.String);
    public static void endSection();
    public static boolean isEnabled();
    public static void beginAsyncSection(java.lang.String,int);
    public static void endAsyncSection(java.lang.String,int);
    public static void setCounter(java.lang.String,int);
    public static void forceEnableAppTracing();
}

# Rhino 1.9.1's optional JavaToJSONConverters.BEAN lambda references desktop-only
# JavaBean introspection. ModernReadability supplies a fail-closed converter and
# never wraps/exposes Java objects, so this path is unreachable. Exclude only these
# five types, not java.beans.*, Rhino generally, or all missing-class diagnostics.
# Revalidate the minified device smoke whenever the Rhino version/context changes.
-dontwarn java.beans.BeanDescriptor
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
