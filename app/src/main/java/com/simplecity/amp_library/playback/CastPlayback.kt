package com.simplecity.amp_library.playback

import android.content.Context
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.Log
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.transcode.BitmapBytesTranscoder
import com.bumptech.glide.request.animation.GlideAnimation
import com.bumptech.glide.request.target.SimpleTarget
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadOptions
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import com.simplecity.amp_library.http.HttpServer
import com.simplecity.amp_library.model.Song
import com.simplecity.amp_library.playback.Playback.Callbacks
import com.simplecity.amp_library.utils.LogUtils
import com.simplecity.amp_library.utils.PlaceholderProvider
import com.simplecity.amp_library.utils.ShuttleUtils
import org.json.JSONException

class CastPlayback(context: Context, castSession: CastSession) : Playback {

    private val applicationContext: Context = context.applicationContext

    private val remoteMediaClient: RemoteMediaClient = castSession.remoteMediaClient
    private val remoteMediaClientCallback: RemoteMediaClient.Callback

    private var currentPosition = 0L

    // remoteMediaClient.isPlaying() returns true momentarily after it is paused, so we use this to track whether
    // it really is playing, based on calls to play(), pause(), stop() and load()
    private var isMeantToBePlaying = false

    init {
        remoteMediaClientCallback = CastMediaClientCallback()
    }

    private fun isConnected(): Boolean {
        val castSession = CastContext.getSharedInstance(applicationContext).sessionManager.currentCastSession
        return castSession?.isConnected ?: false
    }

    override var isInitialized: Boolean = false

    override val isPlaying: Boolean
        get() {
            return remoteMediaClient.isPlaying || isMeantToBePlaying
        }

    override val position: Long
        get() {
            return remoteMediaClient.approximateStreamPosition
        }

    override val audioSessionId: Int
        get() = 0

    override val duration: Long
        get() {
            return remoteMediaClient.streamDuration
        }

    override var callbacks: Callbacks? = null

    override fun setVolume(volume: Float) {
        // Nothing to do
    }

    override fun load(song: Song, playWhenReady: Boolean, seekPosition: Long, completion: ((Boolean) -> Unit)?) {

        HttpServer.getInstance().start()
        HttpServer.getInstance().serveAudio(song.path)
        HttpServer.getInstance().clearImage()

        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK)
        metadata.putString(MediaMetadata.KEY_ALBUM_ARTIST, song.albumArtistName)
        metadata.putString(MediaMetadata.KEY_ALBUM_TITLE, song.albumName)
        metadata.putString(MediaMetadata.KEY_TITLE, song.name)
        metadata.addImage(WebImage(Uri.parse("http://" + ShuttleUtils.getIpAddr() + ":5000" + "/image/" + song.id)))

        val mediaInfo = MediaInfo.Builder("http://" + ShuttleUtils.getIpAddr() + ":5000" + "/audio/" + song.id)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType("audio/*")
            .setMetadata(metadata)
            .build()

        fun doLoad() {
            remoteMediaClient.load(
                mediaInfo, MediaLoadOptions.Builder()
                    .setPlayPosition(seekPosition)
                    .setAutoplay(playWhenReady)
                    .build()
            )

            if (playWhenReady) {
                isMeantToBePlaying = true
            }

            isInitialized = true

            completion?.invoke(true)
        }

        Glide.with(applicationContext).load(song)
            .asBitmap()
            .transcode(BitmapBytesTranscoder(), ByteArray::class.java)
            .placeholder(PlaceholderProvider.getInstance().getPlaceHolderDrawable(song.name, true))
            .into(object : SimpleTarget<ByteArray>() {
                override fun onResourceReady(resource: ByteArray, glideAnimation: GlideAnimation<in ByteArray>?) {
                    HttpServer.getInstance().serveImage(resource)
                    doLoad()
                }

                override fun onLoadFailed(e: Exception?, errorDrawable: Drawable?) {
                    super.onLoadFailed(e, errorDrawable)
                    // Todo: Serve error drawable
                    doLoad()
                }
            })
    }

    override fun willResumePlayback(): Boolean {
        return false
    }

    override fun setNextDataSource(path: String?) {
        // Nothing to do
    }

    override fun release() {
        HttpServer.getInstance().stop()
    }

    override fun seekTo(position: Long) {
        currentPosition = position
        try {
            if (remoteMediaClient.hasMediaSession()) {
                remoteMediaClient.seek(position)
            } else {
                Log.e(TAG, "Seek failed, no remote media session")
            }
        } catch (e: JSONException) {
            LogUtils.logException(TAG, "Exception pausing cast playback", e)
            if (callbacks != null) {
                callbacks?.onError(this, e.message ?: "Unspecified error")
            }
        }
    }

    override fun pause(fade: Boolean) {
        isMeantToBePlaying = false;
        try {
            if (remoteMediaClient.hasMediaSession()) {
                remoteMediaClient.pause()
                currentPosition = remoteMediaClient.approximateStreamPosition
            } else {
                Log.e(TAG, "Pause failed, no remote media session")
            }
        } catch (e: JSONException) {
            LogUtils.logException(TAG, "Exception pausing cast playback", e)
            callbacks?.onError(this, e.message ?: "Unspecified error")
        }
    }

    override fun stop() {
        isMeantToBePlaying = false;

        remoteMediaClient.unregisterCallback(remoteMediaClientCallback)

        if (remoteMediaClient.hasMediaSession()) {
            remoteMediaClient.stop()
        }

        release()
    }

    override fun start() {
        isMeantToBePlaying = true;

        remoteMediaClient.registerCallback(remoteMediaClientCallback)

        if (remoteMediaClient.hasMediaSession() && !remoteMediaClient.isPlaying) {
            remoteMediaClient.play()
        } else {
            Log.e(TAG, "start() failed.. hasMediaSession " + remoteMediaClient.hasMediaSession())
        }
    }

    override fun updateLastKnownStreamPosition() {
        currentPosition = position
    }

    override val resumeWhenSwitched: Boolean = true

    private fun updatePlaybackState() {
        val status = remoteMediaClient.playerState
        val idleReason = remoteMediaClient.idleReason

        // Convert the remote playback states to media playback states.
        when (status) {
            MediaStatus.PLAYER_STATE_IDLE -> {
                Log.d(TAG, "onRemoteMediaPlayerStatusUpdated... IDLE")
                if (idleReason == MediaStatus.IDLE_REASON_FINISHED) callbacks?.onPlaybackComplete(this)
            }
            MediaStatus.PLAYER_STATE_PLAYING -> {
                Log.d(TAG, "onRemoteMediaPlayerStatusUpdated.. PLAYING")
                callbacks?.onPlayStateChanged(this)
            }
            MediaStatus.PLAYER_STATE_PAUSED -> {
                Log.d(TAG, "onRemoteMediaPlayerStatusUpdated.. PAUSED")
                callbacks?.onPlayStateChanged(this)
            }
            else -> {
                Log.d(TAG, "State default : $status")
            }
        }
    }

    companion object {
        const val TAG = "CastPlayback"
    }

    private inner class CastMediaClientCallback : RemoteMediaClient.Callback() {

        override fun onMetadataUpdated() {
            Log.d(TAG, "RemoteMediaClient.onMetadataUpdated")
        }

        override fun onStatusUpdated() {
            Log.d(TAG, "RemoteMediaClient.onStatusUpdated")
            updatePlaybackState()
        }
    }
}