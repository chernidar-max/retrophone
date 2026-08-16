package com.retro.crttvwallpaper

import android.content.Context
import android.opengl.GLSurfaceView
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder

class CrtWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine {
        return CrtEngine()
    }

    inner class CrtEngine : Engine() {
        private var glSurfaceView: WallpaperGLSurfaceView? = null
        private var renderer: CrtRenderer? = null

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)

            renderer = CrtRenderer(this@CrtWallpaperService)
            glSurfaceView = WallpaperGLSurfaceView(this@CrtWallpaperService).apply {
                setEGLContextClientVersion(2)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) {
                glSurfaceView?.onResume()
                // Ефект "прогріву ламп" (шум -> картинка) щоразу при поверненні
                // до робочого столу — так само, як умикання справжнього телевізора.
                renderer?.triggerTurnOn()
            } else {
                glSurfaceView?.onPause()
            }
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
