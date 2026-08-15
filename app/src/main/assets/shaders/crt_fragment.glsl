precision mediump float;

varying vec2 v_TexCoord;

uniform float u_Time;            // Час у секундах
uniform vec2 u_Resolution;       // Роздільна здатність екрана
uniform float u_CollapseProgress; // 0.0 = відкрито, 1.0 = схлопнуто в крапку
uniform float u_WarmupProgress;   // 0.0 = тільки білий шум, 1.0 = CRT зображення
uniform float u_EffectsFade;      // 1.0 = повний CRT ефект, 0.0 = чисте вихідне фото без спотворень
uniform float u_NoiseIntensity;  // Інтенсивність шуму
uniform float u_Curvature;       // Опуклість скла кінескопа
uniform float u_ScanlineIntensity; // Інтенсивність рядків розгортки
uniform sampler2D u_Texture;     // Базова текстура / фото

float random(vec2 st) {
    return fract(sin(dot(st.xy, vec2(12.9898, 78.233))) * 43758.5453123);
}

vec2 curveScreen(vec2 uv, float curVal) {
    uv = (uv - 0.5) * 2.0;
    uv *= 1.1;
    uv.x *= 1.0 + pow((abs(uv.y) / 5.0), 2.0) * curVal * 10.0;
    uv.y *= 1.0 + pow((abs(uv.x) / 4.0), 2.0) * curVal * 10.0;
    uv = (uv / 2.0) + 0.5;
    return uv;
}

void main() {
    vec2 rawUV = v_TexCoord;
    vec2 uv = rawUV;

    // 0. Чистий вихідний колір обраного фото
    vec4 cleanPhoto = texture2D(u_Texture, rawUV);

    // 1. АНІМАЦІЯ СХЛОПУВАННЯ (CRT Turn-off в крапку)
    if (u_CollapseProgress > 0.0) {
        float p = clamp(u_CollapseProgress, 0.0, 1.0);
        
        float verticalScale = 1.0;
        if (p < 0.6) {
            float t = p / 0.6;
            verticalScale = max(0.003, pow(1.0 - t, 3.0));
        } else {
            verticalScale = 0.003;
        }

        float horizontalScale = 1.0;
        if (p >= 0.45) {
            float t = (p - 0.45) / 0.55;
            horizontalScale = max(0.002, pow(1.0 - t, 4.0));
        }

        vec2 centerUV = uv - vec2(0.5);
        centerUV.x /= horizontalScale;
        centerUV.y /= verticalScale;
        uv = centerUV + vec2(0.5);

        if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
            gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
            return;
        }
    }

    // 2. ВИКРИВЛЕННЯ СКЛЯНОЇ КОЛБИ
    vec2 crtUV = curveScreen(uv, u_Curvature * u_EffectsFade);
    if (crtUV.x < 0.0 || crtUV.x > 1.0 || crtUV.y < 0.0 || crtUV.y > 1.0) {
        gl_FragColor = vec4(0.01, 0.01, 0.02, 1.0);
        return;
    }

    // 3. ХРОМАТИЧНА АБЕРАЦІЯ
    float ab = 0.003 * u_EffectsFade;
    float r = texture2D(u_Texture, vec2(crtUV.x + ab, crtUV.y)).r;
    float g = texture2D(u_Texture, crtUV).g;
    float b = texture2D(u_Texture, vec2(crtUV.x - ab, crtUV.y)).b;
    vec3 imgColor = vec3(r, g, b);

    // 4. БІЛИЙ ШУМ ДЛЯ ПРОГРІВУ ЛАМП (Warm-up)
    float staticNoise = random(crtUV * (u_Time + 10.0));
    vec3 noiseColor = vec3(staticNoise * 0.75 + 0.1);

    vec3 color = mix(noiseColor, imgColor, u_WarmupProgress);

    // Додаткові аналогові перешкоди та VCR bar
    float extraNoise = random(crtUV * u_Time) * u_NoiseIntensity * u_EffectsFade;
    float rollingBar = sin(crtUV.y * 3.0 - u_Time * 4.0);
    if (rollingBar > 0.95) {
        extraNoise += 0.15 * (rollingBar - 0.95) * 20.0 * u_EffectsFade;
    }
    color += vec3(extraNoise * u_WarmupProgress);

    // 5. РЯДКИ РОЗГОРТКИ (Scanlines)
    float scanline = sin(crtUV.y * u_Resolution.y * 1.5) * u_ScanlineIntensity * u_EffectsFade;
    color -= scanline;

    // 6. ВІНЬЄТУВАННЯ ТА ТРЕМТІННЯ
    float flicker = sin(u_Time * 60.0) * 0.015 * u_EffectsFade;
    color += flicker;

    float vignette = crtUV.x * crtUV.y * (1.0 - crtUV.x) * (1.0 - crtUV.y);
    color *= clamp(pow(16.0 * vignette, 0.35 * u_EffectsFade), 0.0, 1.0);

    if (u_CollapseProgress > 0.0) {
        float glow = (1.0 - u_CollapseProgress) * 3.5 + 1.0;
        color *= glow;
        color += vec3(0.3 * (1.0 - u_CollapseProgress));
    }

    // 7. ПЛАВНИЙ ПЕРЕХІД У ЧИСТЕ СТАТИЧНЕ ФОТО
    vec3 finalColor = mix(cleanPhoto.rgb, color, u_EffectsFade);

    gl_FragColor = vec4(clamp(finalColor, 0.0, 1.0), 1.0);
}
