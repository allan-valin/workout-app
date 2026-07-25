# Project-specific ProGuard rules. Keep empty until a library needs an exception.

# --- Spotify App Remote (app/libs/spotify-app-remote-release-0.8.0.aar) ---
# The AAR ships BOTH gson and Jackson mappers; we only pull gson, so R8 sees the Jackson
# serializers as missing classes. Same for an internal annotation type that isn't shipped.
-dontwarn com.fasterxml.jackson.**
-dontwarn com.spotify.base.annotations.**

# Protocol types cross the process boundary as JSON and are mapped by field NAME, so they
# must not be renamed or stripped — obfuscating them silently empties PlayerState/Track and
# breaks the library state behind the heart button.
-keep class com.spotify.protocol.types.** { *; }
-keep class com.spotify.protocol.mappers.** { *; }
-keep class com.spotify.android.appremote.api.** { *; }
