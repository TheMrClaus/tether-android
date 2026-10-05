# R8 rules for the release build (T14.1: minify + resource shrinking).
#
# Most dependencies ship their own consumer rules inside the AAR/JAR, and R8 applies them
# automatically: Compose, AndroidX (lifecycle, navigation, activity, datastore), Room
# (keeps RoomDatabase subclasses and the generated *_Impl classes it loads by Class.forName),
# Credential Manager (keeps androidx.credentials.playservices provider classes it loads by
# reflection), Firebase Messaging / Play services, OkHttp 5 (META-INF/proguard/okhttp3.pro),
# kotlinx.coroutines (ServiceLoader'd MainDispatcherFactory, volatile fields) and
# kotlinx.serialization (library-side). The rules below cover what those do NOT: our own
# reflection-by-name entry points, and the kotlinx.serialization shapes our own @Serializable
# classes depend on (the official set from the kotlinx.serialization README, restated so a
# change in the library's bundled rules cannot silently break the wire protocol).
#
# Reflection audit (grep of every module, main source sets): there is no Class.forName,
# kotlin-reflect, JNI (System.loadLibrary) or ServiceLoader use in this codebase. The only
# ::class.java uses are Intent targets (MainActivity), Activity/Service classes named in the
# manifest (kept by the AGP-generated aapt rules), getSystemService(X::class.java) (framework
# classes) and `::class.java.simpleName` as a Compose contentType (an opaque stable label,
# unique per class even when obfuscated). No keep is needed for any of them.

# ---- Debuggable crash traces: keep file + line tables, map them with mapping.txt. ----
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- kotlinx.serialization (official rules) ----
# Generated serializers are found through the Companion's serializer() and the $$serializer
# object; all of the wire protocol (core:protocol ServerMessage / ClientMessage / Projection /
# Overview / Session models, decoded with TetherJson) goes through them.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-dontnote kotlinx.serialization.SerializationKt

# Keep Companion of @Serializable classes (serializer() is looked up on it).
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# Keep serializer() on companions of @Serializable classes (named and unnamed companions).
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep INSTANCE.serializer() of @Serializable objects.
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# The $$serializer classes themselves (referenced from the Companion by name).
-keepclassmembers class **$$serializer {
    *** INSTANCE;
    *** descriptor;
}

# Hand-written serializers (KeepNullJsonElement in core:protocol, any custom KSerializer) are
# looked up via @Serializable(with = ...) and kept reachable by the rules above; keep their
# descriptor so SerialDescriptor equality/lookup survives.
-keep class * implements kotlinx.serialization.KSerializer { *; }
