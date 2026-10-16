# Regeln für den Release-Build (R8 verkleinert und optimiert den Code)

# TagLib ruft seine Klassen aus nativem Code (JNI) auf. Diese Namen dürfen nicht verändert werden.
-keep class com.kyant.taglib.** { *; }
-keepclasseswithmembers class * {
    native <methods>;
}

# Media3 bringt eigene Regeln mit, diese Hinweise sind harmlos
-dontwarn androidx.media3.**
-dontwarn org.checkerframework.**
-dontwarn javax.annotation.**
