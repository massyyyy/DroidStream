package org.droidstream.host

import android.content.Intent
import android.media.projection.MediaProjection

object ProjectionHolder {
    @Volatile var resultCode: Int = 0
    @Volatile var data: Intent? = null
    @Volatile var mediaProjection: MediaProjection? = null
    fun has(): Boolean = data != null || mediaProjection != null
}
