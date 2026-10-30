# kotlinx.serialization DTOs used by the updaters.
-keepclassmembers @kotlinx.serialization.Serializable class io.github.arnavdugad.metropulse.core.data.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
