# kotlinx.serialization: keep generated serializers and @Serializable models.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class nl.rass.player.**$$serializer { *; }
-keepclassmembers class nl.rass.player.** {
    *** Companion;
}
-keepclasseswithmembers class nl.rass.player.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Xray engine (libv2ray AAR): JNI bridge classes are looked up by name from native code.
-keep class go.** { *; }
-keep class libv2ray.** { *; }
