# Rhino 1.9.1 Context creates this interpreter by its class name and no-arg constructor.
-keep class org.mozilla.javascript.Interpreter { public <init>(); }
# Rhino discovers its RegExp implementation through META-INF/services/RegExpLoader.
-keep class org.mozilla.javascript.regexp.RegExpLoaderImpl { public <init>(); }
