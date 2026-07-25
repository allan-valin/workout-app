# Project-specific ProGuard rules. Keep empty until a library needs an exception.

# --- Spotify App Remote (app/libs/spotify-app-remote-release-0.8.0.aar) ---
# The AAR ships BOTH gson and Jackson mappers; we only pull gson, so R8 sees the Jackson
# serializers as missing classes. Same for an internal annotation type that isn't shipped.
-dontwarn com.fasterxml.jackson.**
-dontwarn com.spotify.base.annotations.**

# Protocol types cross the process boundary as JSON and are mapped by field NAME, and the
# mappers/connector are reached reflectively, so nothing in the SDK may be renamed or
# stripped: obfuscation empties PlayerState/Track and can break the connection itself with
# no visible error. The library is ~130 KB, so keeping all of it costs nothing worth saving.
-keep class com.spotify.** { *; }
-keepclassmembers class com.spotify.** { *; }
