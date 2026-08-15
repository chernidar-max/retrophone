package com.retro.crttvwallpaper

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var renderer: CrtRenderer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        glSurfaceView = findViewById(R.id.glSurfacePreview)
        renderer = CrtRenderer(this)

        glSurfaceView.setEGLContextClientVersion(2)
        glSurfaceView.setRenderer(renderer)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        // Тестова кнопка вимкнення кінескопа (схлопування в точку)
        findViewById<Button>(R.id.btnTestTurnOff).setOnClickListener {
            renderer.triggerCrtTurnOff()
        }

        // Кнопка увімкнення назад
        findViewById<Button>(R.id.btnTurnOn).setOnClickListener {
            renderer.resetState()
        }

        // Кнопка встановлення як шпалери Android
        findViewById<Button>(R.id.btnApplyWallpaper).setOnClickListener {
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(this@MainActivity, CrtWallpaperService::class.java)
                )
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Встановіть шпалери через меню налаштувань екрана", Toast.LENGTH_LONG).show()
            }
        }

        // Повзунок шуму / перешкод
        findViewById<SeekBar>(R.id.seekNoise).apply {
            progress = (renderer.noiseIntensity * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                    renderer.noiseIntensity = prog / 100.0f
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }

        // Повзунок викривлення екрана
        findViewById<SeekBar>(R.id.seekCurvature).apply {
            progress = (renderer.curvature * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                    renderer.curvature = prog / 100.0f
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
    }

    override fun onResume() {
        super.onResume()
        glSurfaceView.onResume()
    }

    override fun onPause() {
        super.onPause()
        glSurfaceView.onPause()
    }
}
