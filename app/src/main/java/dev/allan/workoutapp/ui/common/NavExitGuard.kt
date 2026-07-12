package dev.allan.workoutapp.ui.common

/**
 * Lets a screen intercept bottom-nav tab taps the way it already intercepts system back.
 * The workout editor registers a handler while it has unsaved edits; AppBottomBar routes
 * every tab tap through the handler (which shows its keep/discard dialog and calls
 * `proceed` once resolved) instead of navigating straight past the guard.
 *
 * Single-slot on purpose: only one guarded screen is ever visible at a time, and the
 * registering screen clears the slot in onDispose.
 */
object NavExitGuard {
    @Volatile
    var handler: ((proceed: () -> Unit) -> Unit)? = null
}
