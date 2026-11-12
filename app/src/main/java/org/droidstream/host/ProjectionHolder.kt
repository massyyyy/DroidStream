package org.droidstream.host

import android.content.Intent

object ProjectionHolder {
    @Volatile var resultCode: Int = 0
    @Volatile var data: Intent? = null
    fun has(): Boolean = data != null
}
