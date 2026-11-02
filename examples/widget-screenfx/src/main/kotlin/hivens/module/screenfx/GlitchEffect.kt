package hivens.module.screenfx

/**
 * A picture that tears: bursts of colour split, jittered blocks, ghosting and scanlines, with quiet
 * between the bursts.
 *
 * Ported from the GlitchFlicker transition of the GL Transitions collection by bread, MIT. The
 * transition flashes between an outgoing and an incoming image on a 24 frame curve. Here both are
 * the interface, offset against each other, and the clock replays the curve every two seconds.
 * The person's strength scales how hard each burst hits.
 */
internal object GlitchEffect {
    val effect = ShaderEffect("screenfx.glitch", "Glitchcore", """
uniform shader content;
uniform float2 resolution;
uniform float time;
uniform float strength;

const float frameRate = 24.0;
const float rgbOffset = 0.014;
const float blockAmount = 0.72;
const float ghostAmount = 0.62;
const float redCyan = 0.58;
const float scanline = 0.075;

float sat(float x) { return clamp(x, 0.0, 1.0); }

float hash12(float2 p) {
    p = fract(p * float2(443.8975, 397.2973));
    p += dot(p, p.yx + 19.19);
    return fract(p.x * p.y);
}

float2 safeUv(float2 uv) { return clamp(uv, float2(0.001), float2(0.999)); }

float frameReveal(float f) {
    if (f < 0.5) return 0.00;
    if (f < 1.5) return 0.28;
    if (f < 2.5) return 0.43;
    if (f < 3.5) return 0.46;
    if (f < 4.5) return 0.38;
    if (f < 5.5) return 0.48;
    if (f < 6.5) return 0.26;
    if (f < 7.5) return 0.10;
    if (f < 8.5) return 0.00;
    if (f < 9.5) return 0.00;
    if (f < 10.5) return 0.30;
    if (f < 11.5) return 0.58;
    if (f < 12.5) return 1.00;
    if (f < 13.5) return 0.70;
    if (f < 14.5) return 0.42;
    if (f < 15.5) return 0.55;
    if (f < 16.5) return 0.72;
    if (f < 17.5) return 0.88;
    if (f < 18.5) return 0.34;
    if (f < 19.5) return 0.48;
    if (f < 20.5) return 0.56;
    if (f < 21.5) return 0.76;
    if (f < 22.5) return 0.93;
    return 1.00;
}

float frameGlitch(float f) {
    if (f < 0.5) return 0.00;
    if (f < 6.5) return 0.92;
    if (f < 10.5) return 0.38;
    if (f < 11.5) return 0.78;
    if (f < 12.5) return 0.12;
    if (f < 17.5) return 0.74;
    if (f < 22.5) return 0.88;
    if (f < 23.5) return 0.28;
    return 0.00;
}

half4 sampleAt(float2 uv) { return content.eval(safeUv(uv) * resolution); }

half4 chromaFrom(float2 uv, float amt) {
    float2 o = float2(amt, 0.0);
    half4 center = sampleAt(uv);
    return half4(sampleAt(uv + o).r, center.g, sampleAt(uv - o).b, center.a);
}

half4 chromaTo(float2 uv, float amt) {
    float2 o = float2(amt, 0.0);
    half4 center = sampleAt(uv);
    return half4(sampleAt(uv - o).r, center.g, sampleAt(uv + o).b, center.a);
}

float blockMask(float2 uv, float f) {
    float2 big = floor(uv * float2(4.0, 2.0));
    float2 small = floor(uv * float2(8.0, 4.0));
    float wide = step(0.48, hash12(float2(big.x, f * 1.37)));
    float chunks = step(0.56, hash12(small + float2(f * 2.11, f * 0.73)));
    float verticalCut = smoothstep(-0.035, 0.035, uv.x - mix(0.18, 0.78, hash12(float2(f, 4.7))));
    return sat(mix(wide, chunks, 0.38) * 0.72 + verticalCut * 0.28);
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / resolution;
    float progress = fract(time / 2.0);

    float f = floor(progress * frameRate);
    float reveal = frameReveal(f);
    float glitch = frameGlitch(f) * strength;
    if (glitch <= 0.0) return content.eval(fragCoord);

    float rnd = hash12(float2(f, 9.13));
    float2 jitter = float2((rnd - 0.5) * 0.042, (hash12(float2(f, 2.71)) - 0.5) * 0.010) * glitch;

    float2 fromUv = safeUv(uv + jitter);
    float2 toUv = safeUv(uv - jitter * 0.55);

    float block = blockMask(uv, f);
    float localReveal = sat(
        reveal +
        (block - 0.5) * blockAmount * glitch +
        (hash12(float2(f, floor(uv.y * 9.0))) - 0.5) * 0.18 * glitch
    );
    localReveal = smoothstep(0.22, 0.78, localReveal);

    half4 oldClip = chromaFrom(fromUv, rgbOffset * glitch);
    half4 newClip = chromaTo(toUv, rgbOffset * glitch);

    half4 color = mix(oldClip, newClip, half(localReveal));

    float leftWash = (1.0 - smoothstep(0.10, 0.78, uv.x)) * glitch;
    float cyanWash = smoothstep(0.08, 0.62, uv.x) * (1.0 - smoothstep(0.86, 1.0, uv.x)) * glitch;

    half3 redGhost = oldClip.rgb * half3(1.34, 0.42, 0.38);
    half3 cyanGhost = newClip.rgb * half3(0.48, 1.12, 1.24);

    color.rgb = mix(color.rgb, redGhost, half(leftWash * redCyan * 0.42));
    color.rgb = mix(color.rgb, cyanGhost, half(cyanWash * redCyan * 0.30));

    float oldReturn = glitch * (1.0 - smoothstep(0.94, 1.0, reveal));
    oldReturn *= 0.18 + 0.52 * (1.0 - abs(localReveal - 0.5) * 2.0);
    color.rgb = mix(color.rgb, oldClip.rgb, half(oldReturn * ghostAmount));

    float lines = sin((uv.y + f * 0.017) * resolution.y);
    color.rgb += half(lines * scanline * glitch);

    float exposurePulse = (hash12(float2(f, 12.4)) - 0.35) * 0.10 * glitch;
    color.rgb += half(exposurePulse);

    return half4(clamp(color.rgb, half3(0.0), half3(1.0)), color.a);
}
""")
}
