package org.droidstream.host

object NativeBridge {
    init { System.loadLibrary("droidstream_native") }
    external fun info(): String
    external fun enetStart(port: Int): Boolean
    external fun enetStop()
    external fun sendVideoFrame(data: ByteArray, frameIndex: Int, isKeyframe: Boolean, remoteAddr: String, remotePort: Int): Int
}
