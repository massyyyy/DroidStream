package org.droidstream.host

object NativeBridge {
    init { System.loadLibrary("droidstream_native") }
    external fun info(): String
    external fun enetStart(port: Int): Boolean
    external fun enetStop()
}
