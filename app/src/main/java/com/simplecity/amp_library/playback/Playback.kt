package com.simplecity.amp_library.playback

import com.simplecity.amp_library.model.Song

interface Playback {

    var isInitialized: Boolean

    val isPlaying: Boolean

    val position: Long

    val audioSessionId: Int

    val duration: Long

    var callbacks: Callbacks?

    fun setVolume(volume: Float)

    fun load(song: Song, playWhenReady: Boolean, seekPosition: Long, completion: ((Boolean) -> Unit)?)

    fun willResumePlayback(): Boolean

    fun setNextDataSource(path: String?)

    fun release()

    fun seekTo(position: Long)

    fun pause(fade: Boolean)

    fun stop()

    fun start()

    fun updateLastKnownStreamPosition()

    val resumeWhenSwitched: Boolean

    interface Callbacks {

        /**
         * Called when a song ends.
         */
        fun onPlaybackComplete(playback: Playback)

        /**
         * Called when the [Playback] changes to the next track.
         */
        fun onTrackChanged(playback: Playback)

        fun onPlayStateChanged(playback: Playback)

        fun onError(playback: Playback, message: String)
    }
}
