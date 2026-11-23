# R8 / ProGuard rules for the app.
#
# Code shrinking is off today (app/build.gradle.kts: isMinifyEnabled = false), so these rules are
# inert until it is switched on; they are kept here so that switching it on does not break what
# loads classes by name. Prove every rule on a device before relying on it (see the notes below).

# --- Rhino (scripts.run_js) -----------------------------------------------------------------
# Rhino loads many of its own classes by name: lazily initialised built-ins (NativeJSON,
# NativeRegExp, typed arrays, Promise ...), the RegExpLoader service named in
# META-INF/services/org.mozilla.javascript.RegExpLoader, and reflective lookups of its own
# ScriptableObject subclasses. Keep the whole library and its names; the jar is about 1.7 MB.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
-keepnames class org.mozilla.javascript.**
-keep class * implements org.mozilla.javascript.RegExpLoader { <init>(); }

# The jar references desktop-JDK APIs that Android does not have. The code paths that use them
# are never reached here: JavaBeans introspection (LiveConnect bean properties; no Java object
# is ever exposed, the ClassShutter denies every class), jdk.dynalink (the invokedynamic
# linker of the bytecode optimizer) and javax.script (JSR-223, not used). Interpreted mode only.
-dontwarn java.beans.**
-dontwarn jdk.dynalink.**
-dontwarn javax.script.**
-dontwarn javax.lang.model.**

# Lambda and string-concatenation bootstraps in Rhino's bytecode are desugared by D8/R8; its
# serializable lambdas reference SerializedLambda, which Android lacks and Rhino never uses at
# run time (scripts are never serialised).
-dontwarn java.lang.invoke.SerializedLambda
-dontwarn java.lang.invoke.StringConcatFactory
-dontwarn java.lang.invoke.LambdaMetafactory

# --- :framework operator tools ----------------------------------------------------------------
# Nothing extra: the tools, LocalWorkspace and HtmlText use no reflection. TrashItem is
# serialised with kotlinx.serialization through its generated serializer (core), which the
# kotlinx-serialization consumer rules already keep.

# --- Notes ---------------------------------------------------------------------------------
# - Rhino class files are Java 11 (major 55); minSdk 28 is fine (upstream: minSdk 26,
#   interpreted mode only). The jar contains module-info.class at its root, which D8 ignores.
# - Verify after enabling shrinking: run one scripts.run_js call on a device (e.g. the
#   JsSandboxTest "input comes in as JSON..." script) and one regex, which exercises the
#   RegExpLoader service lookup.
