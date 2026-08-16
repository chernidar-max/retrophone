package com.retro.crttvwallpaper

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var renderer: CrtRenderer
    private lateinit var switchPowerLever: View
    private lateinit var switchMenuLever: View
    private lateinit var menuOverlay: View

    private var isPowerOn = true
    private var isMenuOpen = false

    companion object {
        const val PREFS_NAME = "crt_wallpaper_prefs"
        const val KEY_IMAGE_PATH = "background_image_path"
        const val KEY_POWER_ON = "power_on"
    }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            // Копіюємо фото у приватне сховище застосунку (filesDir) одразу при виборі.
            // Це важливо: тримати посилання на зовнішній content:// URI ненадійно —
            // на деяких прошивках (напр. HyperOS) дозвіл на читання може "загубитись"
            // навіть після takePersistableUriPermission, і читання пізніше падає з
            // SecurityException. Власний файл у filesDir завжди доступний застосунку.
            Thread {
                try {
                    val destFile = File(filesDir, "background_image.jpg")
                    contentResolver.openInputStream(uri)?.use { input ->
                        destFile.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw IOException("Не вдалося відкрити обране зображення")

                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putString(KEY_IMAGE_PATH, destFile.absolutePath)
                        .apply()

                    runOnUiThread {
                        renderer.reloadTexture()
                        Toast.makeText(this, "Фон оновлено", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this, "Не вдалося застосувати фото: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        isPowerOn = prefs.getBoolean(KEY_POWER_ON, true)

        glSurfaceView = findViewById(R.id.glSurfacePreview)
        renderer = CrtRenderer(this)
        switchPowerLever = findViewById(R.id.switchPowerLever)
        switchMenuLever = findViewById(R.id.switchMenuLever)
        menuOverlay = findViewById(R.id.menuOverlay)

        glSurfaceView.setEGLContextClientVersion(2)
        glSurfaceView.setRenderer(renderer)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        updatePowerSwitchVisual(animate = false)

        // Головний перемикач: Вкл — живі шпалери з ефектами, Викл — звичайне
        // статичне фото на робочому столі (без ефектів і без live wallpaper).
        findViewById<View>(R.id.switchPowerTrack).setOnClickListener {
            if (isPowerOn) turnPowerOff() else turnPowerOn()
        }

        // Тумблер меню — виводить налаштування прямо на "екрані" телевізора
        findViewById<View>(R.id.switchMenuTrack).setOnClickListener {
            isMenuOpen = !isMenuOpen
            menuOverlay.visibility = if (isMenuOpen) View.VISIBLE else View.GONE
            animateSwitchLever(switchMenuLever, isMenuOpen)
        }
        findViewById<Button>(R.id.btnCloseMenu).setOnClickListener {
            isMenuOpen = false
            menuOverlay.visibility = View.GONE
            animateSwitchLever(switchMenuLever, false)
        }

        // Декоративні ручки — поки що нефункціональні, лише тактильний поворот
        setupDecorativeKnob(R.id.knobChannel)
        setupDecorativeKnob(R.id.knobVolume)
        setupDecorativeKnob(R.id.knobBrightness)

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
        updatePowerSwitchVisual(animate = true)
        renderer.triggerTurnOn()
        launchLiveWallpaperIntent()
        Toast.makeText(this, "Підтвердіть встановлення на наступному екрані", Toast.LENGTH_LONG).show()
    }

    private fun turnPowerOff() {
        isPowerOn = false
        savePowerState(false)
        updatePowerSwitchVisual(animate = true)
        renderer.triggerCrtTurnOff()
        applyStaticWallpaperInBackground()
    }

    /** Анімує важіль тумблера вгору/вниз і перемикає колір (як фізичний rocker-switch). */
    private fun animateSwitchLever(lever: View, on: Boolean) {
        val dp = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics) }
        val targetTranslationY = if (on) -dp(38f) else 0f
        lever.animate()
            .translationY(targetTranslationY)
            .setDuration(180)
            .withEndAction {
                lever.setBackgroundResource(if (on) R.drawable.switch_lever_on_bg else R.drawable.switch_lever_off_bg)
            }
            .start()
    }

    private fun updatePowerSwitchVisual(animate: Boolean) {
        if (animate) {
            animateSwitchLever(switchPowerLever, isPowerOn)
        } else {
            val dp = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics) }
            switchPowerLever.translationY = if (isPowerOn) -dp(38f) else 0f
            switchPowerLever.setBackgroundResource(
                if (isPowerOn) R.drawable.switch_lever_on_bg else R.drawable.switch_lever_off_bg
            )
        }
    }

    /** Декоративна ручка настройки: обертається на клік, нічого не вмикає. */
    private fun setupDecorativeKnob(viewId: Int) {
        val knob = findViewById<View>(viewId)
        var rotation = 0f
        knob.setOnClickListener {
            rotation += 36f
            knob.animate().rotation(rotation).setDuration(120).start()
        }
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
