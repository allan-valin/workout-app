package dev.allan.workoutapp.session

import android.content.Context
import android.util.Log
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import dev.allan.workoutapp.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Spotify App Remote bridge for the session screen.
 *
 * WHY App Remote and not a notification listener: Allan's Redmi (HyperOS) does not render
 * Spotify's heart action in the system media panel, and no app may add a button to another
 * app's notification. App Remote exposes the library directly — UserApi.addToLibrary /
 * removeFromLibrary / getLibraryState — so the heart works regardless of what the notification
 * shows. Cost: Spotify only, needs the Spotify app installed and a client id registered for
 * this applicationId + signing fingerprint.
 *
 * Everything degrades to "unavailable" when the client id is blank (nothing registered yet),
 * Spotify is missing, or the user hasn't opted in — the session screen then looks exactly as
 * it did before.
 */
object SpotifyRemote {

    private const val TAG = "SpotifyRemote"

    data class State(
        val connected: Boolean = false,
        val trackName: String? = null,
        val artist: String? = null,
        /** Spotify URI of the current track — what the heart acts on. */
        val trackUri: String? = null,
        val isPaused: Boolean = true,
        /** Track is in Liked Songs. */
        val saved: Boolean = false,
        /** Spotify says this item can be saved at all (podcast episodes often can't). */
        val canSave: Boolean = false,
        /** Last connection error, for a one-line hint in the UI. */
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var remote: SpotifyAppRemote? = null

    /** True when a client id was compiled in and the Spotify app is present. */
    fun available(context: Context): Boolean =
        BuildConfig.SPOTIFY_CLIENT_ID.isNotBlank() && SpotifyAppRemote.isSpotifyInstalled(context)

    fun connect(context: Context) {
        if (remote?.isConnected == true || !available(context)) return
        val params = ConnectionParams.Builder(BuildConfig.SPOTIFY_CLIENT_ID)
            .setRedirectUri(BuildConfig.SPOTIFY_REDIRECT_URI)
            // Lets Spotify show its own authorization sheet the first time.
            .showAuthView(true)
            .build()
        SpotifyAppRemote.connect(context, params, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                remote = appRemote
                _state.value = _state.value.copy(connected = true, error = null)
                appRemote.playerApi.subscribeToPlayerState().setEventCallback { playerState ->
                    val track = playerState.track
                    val uriChanged = track?.uri != _state.value.trackUri
                    _state.value = _state.value.copy(
                        trackName = track?.name,
                        artist = track?.artist?.name,
                        trackUri = track?.uri,
                        isPaused = playerState.isPaused,
                    )
                    if (uriChanged) track?.uri?.let(::refreshLibraryState)
                }
            }

            override fun onFailure(error: Throwable) {
                Log.w(TAG, "connect failed", error)
                remote = null
                _state.value = State(error = error.message ?: "connection failed")
            }
        })
    }

    fun disconnect() {
        remote?.let(SpotifyAppRemote::disconnect)
        remote = null
        _state.value = State()
    }

    fun togglePlay() {
        val api = remote?.playerApi ?: return
        if (_state.value.isPaused) api.resume() else api.pause()
    }

    fun next() {
        remote?.playerApi?.skipNext()
    }

    fun previous() {
        remote?.playerApi?.skipPrevious()
    }

    /** The point of the whole integration: heart / un-heart the current track. */
    fun toggleSaved() {
        val api = remote?.userApi ?: return
        val uri = _state.value.trackUri ?: return
        val wasSaved = _state.value.saved
        // Optimistic flip so the icon reacts instantly, then confirm with Spotify.
        _state.value = _state.value.copy(saved = !wasSaved)
        val call = if (wasSaved) api.removeFromLibrary(uri) else api.addToLibrary(uri)
        call.setResultCallback { refreshLibraryState(uri) }
            .setErrorCallback {
                Log.w(TAG, "library write failed", it)
                _state.value = _state.value.copy(saved = wasSaved)
            }
    }

    private fun refreshLibraryState(uri: String) {
        val api = remote?.userApi ?: return
        api.getLibraryState(uri).setResultCallback { libraryState ->
            if (libraryState.uri != _state.value.trackUri) return@setResultCallback
            _state.value = _state.value.copy(
                saved = libraryState.isAdded,
                canSave = libraryState.canAdd,
            )
        }
    }
}
