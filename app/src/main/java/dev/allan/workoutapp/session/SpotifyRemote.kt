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
 * Spotify is missing, or the user hasn't opted in — the strip then shows a placeholder.
 *
 * Connection lifetime (Allan, 30/09): ONE connection per process, kept across the app going to
 * the background. 0.8.0 disconnected on every ON_STOP and reconnected on every ON_START, and
 * each reconnect woke Spotify to the foreground on HyperOS ("makes it pop up every time I
 * switch from another app … I end up skipping a song") and reset the state the heart needs
 * ("like button stopped working"). Now: [connect] is a no-op while connected or connecting,
 * the automatic attempt (app launch, setting switched on) never shows Spotify's auth sheet,
 * and a lost connection is only re-tried when the user taps the placeholder strip.
 *
 * OWNS: the one SpotifyAppRemote connection and the [State] the strip renders.
 * MUST NEVER:
 *  - open a second connection ([connect] returns while connected or connecting);
 *  - disconnect on ON_STOP or reconnect on ON_START (30/09 S2a — AppRoot owns the lifetime,
 *    the session screen only reads [state]);
 *  - show Spotify's auth sheet from an automatic call (only a user tap passes showAuth=true);
 *  - clear [State.canSave] on a transient failure (30/09 S2c — the heart stays usable until
 *    Spotify's library call says the item cannot be saved).
 * Shaped by: Phase 34 F2 (the heart), 30/09 S2a/S2b/S2c.
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
    /** A connect is in flight: a second call must not open a second connection. */
    private var connecting = false

    /** True when a client id was compiled in and the Spotify app is present. */
    fun available(context: Context): Boolean =
        BuildConfig.SPOTIFY_CLIENT_ID.isNotBlank() && SpotifyAppRemote.isSpotifyInstalled(context)

    /** Connected, or a connect is already running — nothing for a caller to do. */
    fun isUp(): Boolean = remote?.isConnected == true || connecting

    /**
     * Opens the connection. [showAuth] = true only from an explicit user tap: it lets Spotify
     * show its authorization sheet (first use, or after Spotify forgot us). Automatic calls
     * pass false so nothing ever jumps in front of the workout.
     */
    fun connect(context: Context, showAuth: Boolean = false) {
        if (isUp() || !available(context)) return   // the no-op that keeps it to one connection
        connecting = true
        val params = ConnectionParams.Builder(BuildConfig.SPOTIFY_CLIENT_ID)
            .setRedirectUri(BuildConfig.SPOTIFY_REDIRECT_URI)
            .showAuthView(showAuth)
            .build()
        SpotifyAppRemote.connect(context, params, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                connecting = false
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
                        // Assume the track can be hearted until Spotify says otherwise, so the
                        // heart is usable right away instead of waiting on the library call.
                        canSave = if (uriChanged) track != null else _state.value.canSave,
                        saved = if (uriChanged) false else _state.value.saved,
                    )
                    if (uriChanged) track?.uri?.let(::refreshLibraryState)
                }
            }

            override fun onFailure(error: Throwable) {
                Log.w(TAG, "connect failed", error)
                connecting = false
                remote = null
                _state.value = State(error = error.message ?: "connection failed")
            }
        })
    }

    /** Closes the connection (setting switched off, or the activity is gone for good). */
    fun disconnect() {
        // Called by AppRoot only: setting switched off, or the activity is finishing for good.
        remote?.let(SpotifyAppRemote::disconnect)
        remote = null
        connecting = false
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
                markLostIfDisconnected()
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
        }.setErrorCallback {
            // Keep the optimistic canSave; a failed read is not "cannot be saved".
            Log.w(TAG, "library state failed for $uri", it)
            markLostIfDisconnected()
        }
    }

    /**
     * Spotify closes the connection when its process dies (HyperOS kills it freely). There
     * is no callback for that, so after a failed call we look at the flag and show the
     * placeholder strip again; the user reconnects with a tap.
     */
    private fun markLostIfDisconnected() {
        if (remote?.isConnected == false) {
            remote = null
            _state.value = State(error = "connection lost")
        }
    }
}
