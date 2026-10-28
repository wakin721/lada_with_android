package com.wakin.lada

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.VideoView
import android.widget.Button
import android.view.Gravity
import android.net.Uri

class FullscreenVideoActivity:Activity() {
    private lateinit var video:VideoView
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        val root=FrameLayout(this).apply{setBackgroundColor(android.graphics.Color.BLACK)}
        video=VideoView(this)
        root.addView(video,FrameLayout.LayoutParams(-1,-1,Gravity.CENTER))
        val controller=MediaController(this);controller.setAnchorView(root);video.setMediaController(controller)
        val uri=Uri.parse(intent.getStringExtra("uri")?:run{finish();return})
        require(uri.scheme=="file"||uri.scheme=="content")
        video.setVideoURI(uri)
        video.setOnPreparedListener {video.seekTo(intent.getIntExtra("position",0));video.start()}
        val close=Button(this).apply{text="退出全屏";setOnClickListener{finish()}}
        root.addView(close,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.END).apply{topMargin=18;rightMargin=20})
        setContentView(root)
    }
    override fun onPause(){super.onPause();if(::video.isInitialized)video.pause()}
    override fun onDestroy(){if(::video.isInitialized)video.stopPlayback();super.onDestroy()}
}
