package com.retro.crttvwallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class CrtRenderer(private val context: Context) : GLSurfaceView.Renderer {

    private val vertexCoords = floatArrayOf(
        -1.0f,  1.0f, 0.0f,
        -1.0f, -1.0f, 0.0f,
         1.0f, -1.0f, 0.0f,
         1.0f,  1.0f, 0.0f
    )

    private val texCoords = floatArrayOf(
        0.0f, 0.0f,
        0.0f, 1.0f,
        1.0f, 1.0f,
        1.0f, 0.0f
    )

    private val drawOrder = shortArrayOf(0, 1, 2, 0, 2, 3)

    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(vertexCoords.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(vertexCoords)
            position(0)
        }

    private val texBuffer: FloatBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(texCoords)
            position(0)
        }

    private val drawListBuffer = ByteBuffer.allocateDirect(drawOrder.size * 2)
        .order(ByteOrder.nativeOrder()).asShortBuffer().apply {
            put(drawOrder)
            position(0)
        }

    private var programId = 0
    private var uTimeHandle = 0
    private var uResolutionHandle = 0
    private var uCollapseProgressHandle = 0
    private var uWarmupProgressHandle = 0
    private var uEffectsFadeHandle = 0
    private var uNoiseIntensityHandle = 0
    private var uCurvatureHandle = 0
    private var uScanlineIntensityHandle = 0
    private var uTextureHandle = 0
    private var textureId = 0

    private var startTime = SystemClock.uptimeMillis()
    private var screenWidth = 1080f
    private var screenHeight = 2400f

    @Volatile private var textureReloadRequested = false

    companion object {
        private const val PREFS_NAME = "crt_wallpaper_prefs"
        private const val KEY_IMAGE_URI = "background_image_uri"
    }

    // Параметри ретро-ефектів
    var noiseIntensity = 0.18f
    var curvature = 0.12f
    var scanlineIntensity = 0.12f

    // Анімація схлопування в точку
    // @Volatile — поле читає/пише і GL-потік (onDrawFrame), і головний потік
    // (toggleState/triggerTurnOn з тач-подій та onVisibilityChanged). Без цього
    // JVM не гарантує видимість змін між потоками, і головний потік може ухвалювати
    // рішення на основі застарілого значення.
    @Volatile var collapseProgress = 0.0f
    @Volatile private var isCollapsing = false
    @Volatile private var collapseStartTime = 0L
    private val collapseDuration = 1300L

    // Анімація прогріву ламп (Warmup: шум -> картинка)
    @Volatile private var warmupProgress = 1.0f
    @Volatile private var isWarmingUp = false
    @Volatile private var warmupStartTime = 0L
    private val warmupDuration = 3200L
    private val snowOnlyPhase = 900L

    // Анімація переходу в чисте статичне фото без перешкод
    @Volatile private var effectsFade = 1.0f
    @Volatile private var isFadingToStatic = false
    @Volatile private var fadeStartTime = 0L
    private val fadeDuration = 1000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var autoWakeRunnable: Runnable? = null

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        initShaders()
        initTexture()
        startTime = SystemClock.uptimeMillis()
        triggerTurnOn()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        screenWidth = width.toFloat()
        screenHeight = height.toFloat()
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (textureReloadRequested) {
            textureReloadRequested = false
            applyTexture(loadBitmapForTexture())
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(programId)

        val currentTime = (SystemClock.uptimeMillis() - startTime) / 1000.0f

        // 1. Обробка схлопування в крапку
        if (isCollapsing) {
            val elapsed = SystemClock.uptimeMillis() - collapseStartTime
            collapseProgress = (elapsed.toFloat() / collapseDuration).coerceIn(0.0f, 1.0f)
        }

        // 2. Обробка прогріву ламп
        if (isWarmingUp) {
            val elapsed = SystemClock.uptimeMillis() - warmupStartTime
            if (elapsed < snowOnlyPhase) {
                warmupProgress = 0.0f
            } else {
                val t = ((elapsed - snowOnlyPhase).toFloat() / (warmupDuration - snowOnlyPhase)).coerceIn(0.0f, 1.0f)
                // easeInOutCubic — картинка проявляється повільно на початку і в кінці,
                // а не рівномірно, це відчувається природніше для "прогріву" ЕПТ
                warmupProgress = if (t < 0.5f) {
                    4.0f * t * t * t
                } else {
                    1.0f - (-2.0f * t + 2.0f).let { it * it * it } / 2.0f
                }
                if (t >= 1.0f) isWarmingUp = false
            }
        }

        // 3. Обробка переходу в чисте фото
        if (isFadingToStatic) {
            val elapsed = SystemClock.uptimeMillis() - fadeStartTime
            effectsFade = (1.0f - (elapsed.toFloat() / fadeDuration)).coerceIn(0.0f, 1.0f)
            if (effectsFade <= 0.0f) isFadingToStatic = false
        }

        GLES20.glUniform1f(uTimeHandle, currentTime)
        GLES20.glUniform2f(uResolutionHandle, screenWidth, screenHeight)
        GLES20.glUniform1f(uCollapseProgressHandle, collapseProgress)
        GLES20.glUniform1f(uWarmupProgressHandle, warmupProgress)
        GLES20.glUniform1f(uEffectsFadeHandle, effectsFade)
        GLES20.glUniform1f(uNoiseIntensityHandle, noiseIntensity)
        GLES20.glUniform1f(uCurvatureHandle, curvature)
        GLES20.glUniform1f(uScanlineIntensityHandle, scanlineIntensity)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(uTextureHandle, 0)

        val posHandle = GLES20.glGetAttribLocation(programId, "a_Position")
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer)

        val texHandle = GLES20.glGetAttribLocation(programId, "a_TexCoord")
        GLES20.glEnableVertexAttribArray(texHandle)
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 8, texBuffer)

        GLES20.glDrawElements(GLES20.GL_TRIANGLES, drawOrder.size, GLES20.GL_UNSIGNED_SHORT, drawListBuffer)

        GLES20.glDisableVertexAttribArray(posHandle)
        GLES20.glDisableVertexAttribArray(texHandle)
    }

    fun triggerTurnOn() {
        autoWakeRunnable?.let { mainHandler.removeCallbacks(it) }
        isCollapsing = false
        collapseProgress = 0.0f
        isFadingToStatic = false
        effectsFade = 1.0f

        isWarmingUp = true
        warmupStartTime = SystemClock.uptimeMillis()
        warmupProgress = 0.0f
    }

    fun triggerCrtTurnOff() {
        autoWakeRunnable?.let { mainHandler.removeCallbacks(it) }
        isCollapsing = true
        collapseStartTime = SystemClock.uptimeMillis()
        collapseProgress = 0.0f

        // Через 1.8с після вимкнення плавно проявляємо чисте статичне фото без перешкод
        autoWakeRunnable = Runnable {
            isCollapsing = false
            collapseProgress = 0.0f
            warmupProgress = 1.0f
            isWarmingUp = false

            isFadingToStatic = true
            fadeStartTime = SystemClock.uptimeMillis()
        }.also {
            mainHandler.postDelayed(it, collapseDuration + 700L)
        }
    }

    fun toggleState() {
        if (isCollapsing) {
            triggerTurnOn()
        } else if (isFadingToStatic || effectsFade < 0.5f) {
            triggerTurnOn()
        } else {
            triggerCrtTurnOff()
        }
    }

    fun resetState() {
        triggerTurnOn()
    }

    private fun initShaders() {
        val vertexShaderCode = loadShaderFromAssets("shaders/vertex.glsl")
        val fragmentShaderCode = loadShaderFromAssets("shaders/crt_fragment.glsl")

        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

        programId = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
        }

        uTimeHandle = GLES20.glGetUniformLocation(programId, "u_Time")
        uResolutionHandle = GLES20.glGetUniformLocation(programId, "u_Resolution")
        uCollapseProgressHandle = GLES20.glGetUniformLocation(programId, "u_CollapseProgress")
        uWarmupProgressHandle = GLES20.glGetUniformLocation(programId, "u_WarmupProgress")
        uEffectsFadeHandle = GLES20.glGetUniformLocation(programId, "u_EffectsFade")
        uNoiseIntensityHandle = GLES20.glGetUniformLocation(programId, "u_NoiseIntensity")
        uCurvatureHandle = GLES20.glGetUniformLocation(programId, "u_Curvature")
        uScanlineIntensityHandle = GLES20.glGetUniformLocation(programId, "u_ScanlineIntensity")
        uTextureHandle = GLES20.glGetUniformLocation(programId, "u_Texture")
    }

    private fun initTexture() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        applyTexture(loadBitmapForTexture())
    }

    /** Публічний доступ для MainActivity: коли Power вимикається, це саме зображення
     *  (обране користувачем фото або заглушка за замовчуванням) встановлюється
     *  як звичайне статичне системне тло замість живих шпалер. */
    fun loadCurrentBackgroundBitmap(): Bitmap = loadBitmapForTexture()

    private fun loadBitmapForTexture(): Bitmap {
        val savedUriString = context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_IMAGE_URI, null)

        if (savedUriString != null) {
            try {
                val uri = Uri.parse(savedUriString)
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val bmp = BitmapFactory.decodeStream(stream)
                    if (bmp != null) return bmp
                }
            } catch (e: Exception) {
                // Ignore fallback to default
            }
        }
        return createDefaultSteampunkTexture()
    }

    private fun applyTexture(bitmap: Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
    }

    fun reloadTexture(uri: Uri) {
        textureReloadRequested = true
    }

    private fun createDefaultSteampunkTexture(): Bitmap {
        val w = 1080
        val h = 1920
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.parseColor("#15110E")
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

        paint.color = Color.parseColor("#B87333")
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 6f
        canvas.drawCircle(w / 2f, h / 2f, 320f, paint)
        canvas.drawCircle(w / 2f, h / 2f, 180f, paint)

        paint.strokeWidth = 3f
        canvas.drawLine(w / 2f - 400f, h / 2f, w / 2f + 400f, h / 2f, paint)
        canvas.drawLine(w / 2f, h / 2f - 400f, w / 2f, h / 2f + 400f, paint)

        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#DAA520")
        paint.textSize = 64f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("RETRO CRT • CH-03", w / 2f, h / 2f + 20f, paint)

        paint.textSize = 36f
        paint.color = Color.parseColor("#8B5A2B")
        canvas.drawText("STEAM-POWERED CATHODE TUBE", w / 2f, h / 2f + 80f, paint)

        return bitmap
    }

    private fun loadShaderFromAssets(filename: String): String {
        val sb = StringBuilder()
        context.assets.open(filename).use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                var line: String? = reader.readLine()
                while (line != null) {
                    sb.append(line).append("\n")
                    line = reader.readLine()
                }
            }
        }
        return sb.toString()
    }

    private fun compileShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        return shader
    }
}
