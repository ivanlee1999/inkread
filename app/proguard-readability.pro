# Rhino 1.9.1 Context creates this interpreter by its class name and no-arg constructor.
-keep class org.mozilla.javascript.Interpreter { public <init>(); }
# Rhino discovers its RegExp implementation through META-INF/services/RegExpLoader.
-keep class org.mozilla.javascript.regexp.RegExpLoaderImpl { public <init>(); }

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
