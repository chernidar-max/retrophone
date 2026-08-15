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
        -1.0f,  1.0f, 0.0f,  // Top Left
        -1.0f, -1.0f, 0.0f,  // Bottom Left
         1.0f, -1.0f, 0.0f,  // Bottom Right
         1.0f,  1.0f, 0.0f   // Top Right
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
    private var uNoiseIntensityHandle = 0
    private var uCurvatureHandle = 0
    private var uTextureHandle = 0
    private var textureId = 0

    private var startTime = SystemClock.uptimeMillis()
    private var screenWidth = 1080f
    private var screenHeight = 2400f

    // Перезавантаження текстури відбувається лише в GL-потоці (onDrawFrame),
    // тому запит ставиться у чергу прапорцем, а не викликається напряму.
    @Volatile private var textureReloadRequested = false

    companion object {
        private const val PREFS_NAME = "crt_wallpaper_prefs"
        private const val KEY_IMAGE_URI = "background_image_uri"
    }

    // Параметри анімації та ефектів
    var noiseIntensity = 0.18f
    var curvature = 0.12f
    var collapseProgress = 0.0f
    private var isCollapsing = false
    private var collapseStartTime = 0L
    private val collapseDuration = 550L // 0.55 секунди для ідеального відчуття

    var onCollapseComplete: (() -> Unit)? = null

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        initShaders()
        initTexture()
        startTime = SystemClock.uptimeMillis()
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
        GLES20.glUniform1f(uTimeHandle, currentTime)
        GLES20.glUniform2f(uResolutionHandle, screenWidth, screenHeight)
        GLES20.glUniform1f(uNoiseIntensityHandle, noiseIntensity)
        GLES20.glUniform1f(uCurvatureHandle, curvature)

        // Обробка анімації вимкнення
        if (isCollapsing) {
            val elapsed = SystemClock.uptimeMillis() - collapseStartTime
            collapseProgress = (elapsed.toFloat() / collapseDuration).coerceIn(0.0f, 1.0f)
            if (collapseProgress >= 1.0f) {
                isCollapsing = false
                onCollapseComplete?.invoke()
            }
        }
        GLES20.glUniform1f(uCollapseProgressHandle, collapseProgress)

        // Bind Texture
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

    fun triggerCrtTurnOff() {
        isCollapsing = true
        collapseStartTime = SystemClock.uptimeMillis()
        collapseProgress = 0.0f
    }

    fun resetState() {
        isCollapsing = false
        collapseProgress = 0.0f
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
        uNoiseIntensityHandle = GLES20.glGetUniformLocation(programId, "u_NoiseIntensity")
        uCurvatureHandle = GLES20.glGetUniformLocation(programId, "u_Curvature")
        uTextureHandle = GLES20.glGetUniformLocation(programId, "u_Texture")
    }

    private fun initTexture() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        applyTexture(loadBitmapForTexture())
    }

    /** Завантажує Bitmap для текстури: спершу пробує збережене фото користувача,
     *  якщо його нема або не вдалось прочитати — falls back на процедурний плейсхолдер. */
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
                // Файл видалено, доступ відкликано тощо — тихо падаємо на плейсхолдер.
            }
        }
        return createDefaultSteampunkTexture()
    }

    /** Заливає готовий Bitmap у вже створену GL-текстуру. Викликати лише з GL-потоку. */
    private fun applyTexture(bitmap: Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
    }

    /** Викликати з MainActivity після того, як користувач обрав нове фото.
     *  Uri параметр не використовується напряму — він вже має бути збережений
     *  в SharedPreferences (KEY_IMAGE_URI) на момент виклику; тут лише ставимо прапорець
     *  перечитування, яке безпечно виконається в GL-потоці на наступному кадрі. */
    fun reloadTexture(uri: Uri) {
        textureReloadRequested = true
    }

    private fun createDefaultSteampunkTexture(): Bitmap {
        val w = 1080
        val h = 1920
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Темний вінтажний фон (бронзово-металевий відтінок)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.parseColor("#15110E")
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

        // Ретро тестова сітка телевізора / стімпанк логотип
        paint.color = Color.parseColor("#B87333") // Мідний
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 6f
        canvas.drawCircle(w / 2f, h / 2f, 320f, paint)
        canvas.drawCircle(w / 2f, h / 2f, 180f, paint)

        paint.strokeWidth = 3f
        canvas.drawLine(w / 2f - 400f, h / 2f, w / 2f + 400f, h / 2f, paint)
        canvas.drawLine(w / 2f, h / 2f - 400f, w / 2f, h / 2f + 400f, paint)

        // Текст вінтажного телеканалу
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
