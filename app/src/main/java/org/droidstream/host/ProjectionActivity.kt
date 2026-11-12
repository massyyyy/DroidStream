package org.droidstream.host

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log

class ProjectionActivity : Activity() {
    companion object {
        private const val TAG = "ProjectionActivity"
        private const val REQ = 1001
        fun start(ctx: Context) {
            val i = Intent(ctx, ProjectionActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (ProjectionHolder.has()) { finish(); return }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ) {
            if (resultCode == RESULT_OK && data != null) {
                ProjectionHolder.resultCode = resultCode
                ProjectionHolder.data = data
                Log.i(TAG, "Consenso registrazione schermo ottenuto")
                ScreenCapture.onProjectionGranted()
            } else {
                Log.e(TAG, "Consenso registrazione schermo NEGATO")
            }
        }
        finish()
    }
}
