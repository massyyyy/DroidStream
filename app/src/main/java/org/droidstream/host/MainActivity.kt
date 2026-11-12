override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode == ScreenCapture.REQUEST_CODE_MEDIA_PROJECTION) {
        if (resultCode == Activity.RESULT_OK && data != null) {
            LaunchHandler.screenCapture?.startWithProjection(resultCode, data)
        } else {
            Log.e("MainActivity", "MediaProjection permission denied")
        }
    }
}
