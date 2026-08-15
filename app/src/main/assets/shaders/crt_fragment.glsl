precision mediump float;

varying vec2 v_TexCoord;

uniform float u_Time;            // Час у секундах
uniform vec2 u_Resolution;       // Роздільна здатність екрана
uniform float u_CollapseProgress; // 0.0 = звичайний екран, 1.0 = повністю вимкнено
uniform float u_NoiseIntensity;  // Інтенсивність білого шуму
uniform float u_Curvature;       // Викривлення скла кінескопа (наприклад, 0.15)
uniform sampler2D u_Texture;     // Базова текстура / шпалери

// Псевдовипадковий генератор шуму
float random(vec2 st) {
    return fract(sin(dot(st.xy, vec2(12.9898, 78.233))) * 43758.5453123);
}

// Викривлення кінескопа (Barrel Distortion)
vec2 curveScreen(vec2 uv) {
    uv = (uv - 0.5) * 2.0;
    uv *= 1.1;
    uv.x *= 1.0 + pow((abs(uv.y) / 5.0), 2.0) * u_Curvature * 10.0;
    uv.y *= 1.0 + pow((abs(uv.x) / 4.0), 2.0) * u_Curvature * 10.0;
    uv = (uv / 2.0) + 0.5;
    return uv;
}

void main() {
    vec2 uv = v_TexCoord;

    // --- 1. АНІМАЦІЯ СХЛОПУВАННЯ (CRT Turn Off Effect) ---
    if (u_CollapseProgress > 0.0) {
        float p = clamp(u_CollapseProgress, 0.0, 1.0);
        
        // Фаза 1: Стискання по вертикалі у горизонтальну білу смугу (перші 60% часу)
        float verticalScale = 1.0;
        if (p < 0.6) {
            float t = p / 0.6;
            verticalScale = max(0.003, pow(1.0 - t, 3.0));
        } else {
            verticalScale = 0.003;
        }

        // Фаза 2: Стискання по горизонталі у точку по центру (після 45% часу)
        float horizontalScale = 1.0;
        if (p >= 0.45) {
            float t = (p - 0.45) / 0.55;
            horizontalScale = max(0.002, pow(1.0 - t, 4.0));
        }

        // Центрування та масштабування UV координат
        vec2 centerUV = uv - vec2(0.5);
        centerUV.x /= horizontalScale;
        centerUV.y /= verticalScale;
        uv = centerUV + vec2(0.5);

        // Якщо піксель за межами стиснутої області
        if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
            gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
            return;
        }
    }

    // --- 2. ВИКРИВЛЕННЯ СКЛА КІНЕСКОПА ---
    vec2 crtUV = curveScreen(uv);

    // Чорні рамки за межами викривленого екрана
    if (crtUV.x < 0.0 || crtUV.x > 1.0 || crtUV.y < 0.0 || crtUV.y > 1.0) {
        gl_FragColor = vec4(0.02, 0.02, 0.03, 1.0);
        return;
    }

    // --- 3. ЗМІЩЕННЯ КОЛЬОРІВ (Хроматична аберація) ---
    float aberration = 0.003;
    float r = texture2D(u_Texture, vec2(crtUV.x + aberration, crtUV.y)).r;
    float g = texture2D(u_Texture, crtUV).g;
    float b = texture2D(u_Texture, vec2(crtUV.x - aberration, crtUV.y)).b;
    vec3 color = vec3(r, g, b);

    // --- 4. СТАТИЧНИЙ ШУМ ТА АНАЛОГОВІ ПЕРЕШКОДИ ---
    float noise = random(crtUV * u_Time) * u_NoiseIntensity;
    
    // Біжуча горизонтальна смуга розсинхронізації (VCR roll)
    float rollingBar = sin(crtUV.y * 3.0 - u_Time * 4.0);
    if (rollingBar > 0.95) {
        noise += 0.15 * (rollingBar - 0.95) * 20.0;
        crtUV.x += sin(u_Time * 50.0) * 0.005; // зсув рядка
    }
    color += vec3(noise);

    // --- 5. РЯДКИ РОЗГОРТКИ (Scanlines) ---
    float scanline = sin(crtUV.y * u_Resolution.y * 1.5) * 0.12;
    color -= scanline;

    // Люмінофорна маска (вертикальні субпікселі)
    float phosphor = sin(crtUV.x * u_Resolution.x * 2.0) * 0.05;
    color -= phosphor;

    // --- 6. ТРЕМТІННЯ СВІТІННЯ (Flicker) ТА ВІНЬЄТУВАННЯ ---
    float flicker = sin(u_Time * 60.0) * 0.015;
    color += flicker;

    // Затемнення по кутах (Vignette)
    float vignette = crtUV.x * crtUV.y * (1.0 - crtUV.x) * (1.0 - crtUV.y);
    color *= clamp(pow(16.0 * vignette, 0.35), 0.0, 1.0);

    // Підсилення яскравості у центрі під час схлопування
    if (u_CollapseProgress > 0.0) {
        float glow = (1.0 - u_CollapseProgress) * 3.5 + 1.0;
        color *= glow;
        // Залишкове біле свічення фосфору
        color += vec3(0.3 * (1.0 - u_CollapseProgress));
    }

    gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
