# Shrink library code only. The module's own classes run inside SystemUI, system_server, Settings and
# MiWallpaper, are entered by name (META-INF/xposed/java_init.list) and look up OEM members reflectively,
# so every one of them is kept intact and nothing in the APK is renamed.
-dontobfuscate
# No code optimisation either: module bytecode stays what javac/D8 produce, R8 only drops unused library code.
-dontoptimize
-keep class com.zhuanz.flymemotion.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable

# The service SDK binds to LSPosed through its own provider and AIDL stubs.
-keep class io.github.libxposed.service.** { *; }
# Provided by the framework at runtime (compileOnly).
-dontwarn io.github.libxposed.api.**
