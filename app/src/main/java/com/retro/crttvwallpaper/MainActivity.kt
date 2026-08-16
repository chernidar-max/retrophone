package com.retro.crttvwallpaper

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var renderer: CrtRenderer
    private lateinit var btnPower: Button
    private lateinit var menuOverlay: View

    private var isPowerOn = true

    companion object {
        const val PREFS_NAME = "crt_wallpaper_prefs"
        const val KEY_IMAGE_URI = "background_image_uri"
        const val KEY_POWER_ON = "power_on"
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

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        isPowerOn = prefs.getBoolean(KEY_POWER_ON, true)

        glSurfaceView = findViewById(R.id.glSurfacePreview)
        renderer = CrtRenderer(this)
        btnPower = findViewById(R.id.btnPower)
        menuOverlay = findViewById(R.id.menuOverlay)

        glSurfaceView.setEGLContextClientVersion(2)
        glSurfaceView.setRenderer(renderer)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        updatePowerButtonLabel()

        // Головний перемикач: Вкл — живі шпалери з ефектами, Викл — звичайне
        // статичне фото на робочому столі (без ефектів і без live wallpaper).
        btnPower.setOnClickListener {
            if (isPowerOn) {
                turnPowerOff()
            } else {
                turnPowerOn()
            }
        }

        // Меню налаштувань — виводиться прямо на "екрані" телевізора
        findViewById<Button>(R.id.btnMenu).setOnClickListener {
            menuOverlay.visibility = if (menuOverlay.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<Button>(R.id.btnCloseMenu).setOnClickListener {
            menuOverlay.visibility = View.GONE
        }

        // Декоративні клавіші — поки що нефункціональні
        findViewById<Button>(R.id.btnChDown).setOnClickListener { }
        findViewById<Button>(R.id.btnChUp).setOnClickListener { }

        // Кнопка вибору власного фото як фону для ефекту
        findViewById<Button>(R.id.btnPickImage).setOnClickListener {
            pickImageLauncher.launch(arrayOf("image/*"))
        }

        // Ручне перевстановлення живих шпалер (на випадок, якщо системний
        // діалог після Power-Вкл не пройшов сам по собі)
        findViewById<Button>(R.id.btnApplyWallpaper).setOnClickListener {
            launchLiveWallpaperIntent()
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

        // Відображаємо в превʼю стан, що відповідає збереженому Power-стану
        if (!isPowerOn) {
            renderer.triggerCrtTurnOff()
        }
    }

    private fun turnPowerOn() {
        isPowerOn = true
        savePowerState(true)
        updatePowerButtonLabel()
        renderer.triggerTurnOn()
        launchLiveWallpaperIntent()
        Toast.makeText(this, "Підтвердіть встановлення на наступному екрані", Toast.LENGTH_LONG).show()
    }

    private fun turnPowerOff() {
        isPowerOn = false
        savePowerState(false)
        updatePowerButtonLabel()
        renderer.triggerCrtTurnOff()
        applyStaticWallpaperInBackground()
    }

    private fun updatePowerButtonLabel() {
        btnPower.text = if (isPowerOn) "⏻ УВІМК" else "⏻ ВИМК"
    }

    private fun savePowerState(on: Boolean) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(KEY_POWER_ON, on)
            .apply()
    }

    private fun launchLiveWallpaperIntent() {
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

    /** "Викл" повністю замінює системні шпалери на звичайне статичне фото —
     *  той самий кадр, що використовується як текстура ефекту. Це не потребує
     *  системного діалогу підтвердження (на відміну від живих шпалер) і працює
     *  надійно незалежно від особливостей лаунчера. */
    private fun applyStaticWallpaperInBackground() {
        Thread {
            try {
                val bitmap = renderer.loadCurrentBackgroundBitmap()
                WallpaperManager.getInstance(this).setBitmap(bitmap)
                runOnUiThread {
                    Toast.makeText(this, "Статичне фото встановлено на робочий стіл", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Не вдалося встановити фото: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
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
