# Keep kotlinx.serialization generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class com.photosync.app.data.remote.** {
    *** Companion;
}
-keepclasseswithmembers class com.photosync.app.data.remote.** {
    kotlinx.serialization.KSerializer serializer(...);
}
