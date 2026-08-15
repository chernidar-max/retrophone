package com.retro.crttvwallpaper

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var renderer: CrtRenderer

    companion object {
        const val PREFS_NAME = "crt_wallpaper_prefs"
        const val KEY_IMAGE_URI = "background_image_uri"
    }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                // Постійний доступ до файлу — потрібен, бо шпалери працюють
                // в окремому сервісі і можуть читати Uri навіть після ребута.
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putString(KEY_IMAGE_URI, uri.toString())
                    .apply()
                renderer.reloadTexture(uri)
                Toast.makeText(this, "Фон оновлено", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Не вдалося застосувати фото: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

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

        // Кнопка вибору власного фото як фону для ефекту
        findViewById<Button>(R.id.btnPickImage).setOnClickListener {
            pickImageLauncher.launch(arrayOf("image/*"))
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
