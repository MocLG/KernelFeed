# ---- Apache Mime4J ---------------------------------------------------------------
# Mime4j resolves parts of its parser stack reflectively and ships optional integrations
# (DOM storage backends, log bindings) that are absent here. Keeping its public surface
# avoids R8 removing a class the parser looks up by name at runtime, which would only
# surface as a crash on the first message opened in a release build.
-keep class org.apache.james.mime4j.** { *; }
-dontwarn org.apache.james.mime4j.**

# Mime4j references these optional dependencies but the app does not ship them.
-dontwarn javax.mail.**
-dontwarn org.apache.commons.logging.**

# ---- OkHttp / Retrofit -----------------------------------------------------------
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Retrofit builds the LoreApi implementation from the interface's annotations and
# generic signatures, both of which R8 would otherwise be free to discard.
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-if interface * { @retrofit2.http.* public *** *(...); }
-keep,allowoptimization,allowshrinking,allowobfuscation class <1>

# Kotlin suspend functions on Retrofit interfaces are erased to Continuation params.
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# ---- Room ------------------------------------------------------------------------
# Room's generated implementations are referenced by name from the generated database.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# ---- App models ------------------------------------------------------------------
# Entities are mapped by field name in generated Room code; renaming them breaks the
# column bindings for the FTS shadow table in particular.
-keep class dev.lukag.kernelfeed.data.local.entity.** { *; }
