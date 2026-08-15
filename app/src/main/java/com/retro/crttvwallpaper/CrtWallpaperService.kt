package com.retro.crttvwallpaper

import android.content.Context
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder

class CrtWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine {
        return CrtEngine()
    }

    inner class CrtEngine : Engine() {
        private var glSurfaceView: WallpaperGLSurfaceView? = null
        private var renderer: CrtRenderer? = null
        
        // Надійний власний лічильник подвійного тапу (працює завжди на всіх лаунчерах Android 15)
        private var lastTapTime = 0L
        private var lastTapX = 0f
        private var lastTapY = 0f
        private val doubleTapTimeout = 400L
        private val doubleTapSlopSquare = 120f * 120f

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)

            renderer = CrtRenderer(this@CrtWallpaperService)
            glSurfaceView = WallpaperGLSurfaceView(this@CrtWallpaperService).apply {
                setEGLContextClientVersion(2)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }

            setTouchEventsEnabled(true)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) {
                glSurfaceView?.onResume()
                renderer?.triggerTurnOn()
                // На деяких прошивках система перепідключається до сервісу після
                // розблокування екрана, і прапорець дозволу на тач-події може
                // "забутись" — тому підтверджуємо його при кожному показі шпалер.
                setTouchEventsEnabled(true)
                lastTapTime = 0L
            } else {
                glSurfaceView?.onPause()
            }
        }

        override fun onTouchEvent(event: MotionEvent) {
            super.onTouchEvent(event)
            try {
                if (event.action == MotionEvent.ACTION_DOWN) {
                    val now = SystemClock.uptimeMillis()
                    val dx = event.x - lastTapX
                    val dy = event.y - lastTapY
                    val distSq = dx * dx + dy * dy

                    if (now - lastTapTime <= doubleTapTimeout && distSq <= doubleTapSlopSquare) {
                        // Подвійний тап успішно розпізнано
                        renderer?.toggleState()
                        lastTapTime = 0L // Скидаємо, щоб не спрацьовувало тричі
                    } else {
                        lastTapTime = now
                        lastTapX = event.x
                        lastTapY = event.y
                    }
                }
            } catch (e: Exception) {
                Log.e("CrtWallpaperService", "Помилка обробки дотику", e)
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
