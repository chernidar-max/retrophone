package com.retro.crttvwallpaper

import android.content.Context
import android.opengl.GLSurfaceView
import android.service.wallpaper.WallpaperService
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceHolder

class CrtWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine {
        return CrtEngine()
    }

    inner class CrtEngine : Engine() {
        private var glSurfaceView: WallpaperGLSurfaceView? = null
        private var renderer: CrtRenderer? = null
        private lateinit var gestureDetector: GestureDetector

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)

            renderer = CrtRenderer(this@CrtWallpaperService)
            glSurfaceView = WallpaperGLSurfaceView(this@CrtWallpaperService).apply {
                setEGLContextClientVersion(2)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }

            // Подвійний тап по робочому столу запускає ефект лампового схлопування в точку
            gestureDetector = GestureDetector(this@CrtWallpaperService, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    renderer?.triggerCrtTurnOff()
                    return true
                }

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    renderer?.resetState()
                    return true
                }
            })

            setTouchEventsEnabled(true)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) {
                glSurfaceView?.onResume()
                renderer?.resetState()
            } else {
                glSurfaceView?.onPause()
            }
        }

        override fun onTouchEvent(event: MotionEvent) {
            super.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
        }

        override fun onDestroy() {
            super.onDestroy()
            glSurfaceView?.onDestroy()
            glSurfaceView = null
        }

        inner class WallpaperGLSurfaceView(context: Context) : GLSurfaceView(context) {
            override fun getHolder(): SurfaceHolder {
                return surfaceHolder
            }

            fun onDestroy() {
                super.onDetachedFromWindow()
            }
        }
    }
}
