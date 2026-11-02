package hivens.module.screenfx

/**
 * An old arcade monitor: the interface is sampled as a coarse grid of pixels, bent a little at the
 * edges, drawn with scanlines and a shadow mask, and held in a dark frame.
 *
 * Ported from the CRT scan-line shader by Timothy Lottes, which is in the public domain. Its bloom
 * pass is left out: it is the costly half, and on a phone that is the half that would be felt. The
 * person's strength is how far the monitor takes the interface from its own picture.
 */
internal object RetroEffect {
    val effect = ShaderEffect("screenfx.retro", "Retro", """
uniform shader content;
uniform float2 resolution;
uniform float time;
uniform float strength;

const float hardScan = -8.0;
const float hardPix = -3.0;
const float warpX = 0.031;
const float warpY = 0.041;
const float maskDark = 0.5;
const float maskLight = 1.5;
const float pixelSize = 2.5;

float3 toLinear(float3 c) {
    return float3(
        c.r <= 0.04045 ? c.r / 12.92 : pow((c.r + 0.055) / 1.055, 2.4),
        c.g <= 0.04045 ? c.g / 12.92 : pow((c.g + 0.055) / 1.055, 2.4),
        c.b <= 0.04045 ? c.b / 12.92 : pow((c.b + 0.055) / 1.055, 2.4));
}

float3 toSrgb(float3 c) {
    return float3(
        c.r < 0.0031308 ? c.r * 12.92 : 1.055 * pow(c.r, 0.41666) - 0.055,
        c.g < 0.0031308 ? c.g * 12.92 : 1.055 * pow(c.g, 0.41666) - 0.055,
        c.b < 0.0031308 ? c.b * 12.92 : 1.055 * pow(c.b, 0.41666) - 0.055);
}

// Nearest emulated sample given a position and a texel offset.
float3 fetch(float2 pos, float2 off, float2 src) {
    float2 p = (floor(pos * src + off) + 0.5) / src;
    return toLinear(content.eval(p * resolution).rgb);
}

// Distance in emulated pixels to the nearest texel.
float2 dist(float2 pos, float2 src) {
    pos = pos * src;
    return -((pos - floor(pos)) - float2(0.5));
}

// 1D Gaussian.
float gaus(float pos, float scale) { return exp2(scale * pos * pos); }

// 3-tap Gaussian filter along a horizontal line.
float3 horz3(float2 pos, float off, float2 src) {
    float3 b = fetch(pos, float2(-1.0, off), src);
    float3 c = fetch(pos, float2(0.0, off), src);
    float3 d = fetch(pos, float2(1.0, off), src);
    float dst = dist(pos, src).x;
    float wb = gaus(dst - 1.0, hardPix);
    float wc = gaus(dst + 0.0, hardPix);
    float wd = gaus(dst + 1.0, hardPix);
    return (b * wb + c * wc + d * wd) / (wb + wc + wd);
}

// 5-tap Gaussian filter along a horizontal line.
float3 horz5(float2 pos, float off, float2 src) {
    float3 a = fetch(pos, float2(-2.0, off), src);
    float3 b = fetch(pos, float2(-1.0, off), src);
    float3 c = fetch(pos, float2(0.0, off), src);
    float3 d = fetch(pos, float2(1.0, off), src);
    float3 e = fetch(pos, float2(2.0, off), src);
    float dst = dist(pos, src).x;
    float wa = gaus(dst - 2.0, hardPix);
    float wb = gaus(dst - 1.0, hardPix);
    float wc = gaus(dst + 0.0, hardPix);
    float wd = gaus(dst + 1.0, hardPix);
    float we = gaus(dst + 2.0, hardPix);
    return (a * wa + b * wb + c * wc + d * wd + e * we) / (wa + wb + wc + wd + we);
}

// Scanline weight.
float scan(float2 pos, float off, float2 src) { return gaus(dist(pos, src).y + off, hardScan); }

// The nearest three lines affect the pixel.
float3 tri(float2 pos, float2 src) {
    float3 a = horz3(pos, -1.0, src);
    float3 b = horz5(pos, 0.0, src);
    float3 c = horz3(pos, 1.0, src);
    return a * scan(pos, -1.0, src) + b * scan(pos, 0.0, src) + c * scan(pos, 1.0, src);
}

// Distortion of scanlines.
float2 warp(float2 pos, float amount) {
    pos = pos * 2.0 - 1.0;
    pos *= float2(1.0 + (pos.y * pos.y) * warpX * amount, 1.0 + (pos.x * pos.x) * warpY * amount);
    return pos * 0.5 + 0.5;
}

// Stretched VGA style shadow mask.
float3 mask(float2 pos) {
    float3 m = float3(maskDark);
    pos.x += pos.y * 3.0;
    pos.x = fract(pos.x * 0.166666666);
    if (pos.x < 0.333) m.r = maskLight;
    else if (pos.x < 0.666) m.g = maskLight;
    else m.b = maskLight;
    return m;
}

half4 main(float2 fragCoord) {
    float2 src = resolution / pixelSize;
    float2 pos = warp(fragCoord / resolution, strength);
    float3 crt = tri(pos, src);
    crt *= mix(float3(1.0), mask(fragCoord * 1.000001), strength);
    // Outside the bent screen the monitor's frame is black.
    if (pos.x < 0.0001 || pos.x > 0.9999 || pos.y < 0.0001 || pos.y > 0.9999) crt = float3(0.0);
    half4 original = content.eval(fragCoord);
    return mix(original, half4(half3(toSrgb(crt)), 1.0), half(strength));
}
""")
}
