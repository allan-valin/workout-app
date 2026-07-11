package dev.allan.workoutapp.ui.common

import android.content.Context
import coil.ImageLoader
import coil.decode.ImageDecoderDecoder
import coil.decode.SvgDecoder

/**
 * One process-wide Coil loader (SVG + GIF capable, crossfade on) shared by every screen.
 * Screens used to build their own loader per composition, so the first visit of each
 * screen re-decoded the body SVGs / exercise media from scratch — the "content pops in a
 * second later" report. A single loader keeps decodes in one memory cache, and the
 * crossfade turns any remaining late arrival into a fade instead of a pop.
 */
object AppImageLoader {
    @Volatile private var instance: ImageLoader? = null

    fun get(context: Context): ImageLoader =
        instance ?: synchronized(this) {
            instance ?: ImageLoader.Builder(context.applicationContext)
                .components {
                    add(SvgDecoder.Factory())
                    add(ImageDecoderDecoder.Factory()) // GIF (minSdk 29 ⇒ always available)
                }
                .crossfade(150)
                .build()
                .also { instance = it }
        }
}
