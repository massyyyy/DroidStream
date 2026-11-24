package org.droidstream.host

object NativeBridge {
    init { System.loadLibrary("droidstream_native") }
    external fun info(): String
    external fun enetStart(port: Int): Boolean
    external fun enetStop()
    external fun initVideoPacketizer(): Boolean
    external fun getDiscoveredClientPort(): Int
    external fun resetVideoPacketizer()
    external fun sendVideoFrame(
        data: ByteArray, 
        frameIndex: Int, 
        isKeyframe: Boolean, 
        rtpTimestamp: Int, 
        remoteAddr: String, 
        remotePort: Int
    ): Int

    @JvmStatic
    fun onNativeInput(type: Int, x: Int, y: Int, button: Int, keyCode: Int) {
        InputManager.onInput(type, x, y, button, keyCode)
    }
}
