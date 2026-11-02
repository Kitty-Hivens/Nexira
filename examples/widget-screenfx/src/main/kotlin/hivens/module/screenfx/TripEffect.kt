package hivens.module.screenfx

/**
 * A wavering, folding picture: the interface bends in a slow wave and a spinning kaleidoscope of
 * itself comes and goes through it.
 *
 * Ported from two transitions of the GL Transitions collection, both MIT: Dreamy by mikolalysenko
 * (the wave) and Kaleidoscope by nwoeanhinnogaehr (the fold). Their crossfade between two images is
 * a mix between the interface and its own fold, driven by the clock and the person's strength.
 */
internal object TripEffect {
    val effect = ShaderEffect("screenfx.trip", "Trip", """
uniform shader content;
uniform float2 resolution;
uniform float time;
uniform float strength;

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / resolution;

    // Dreamy (mikolalysenko, MIT): a gentle standing wave across x.
    float drift = 0.5 + 0.5 * sin(time * 0.5);
    float wave = 0.03 * strength * cos(10.0 * (drift + uv.x));
    float2 q = clamp(uv + float2(0.0, wave), float2(0.0), float2(1.0));

    // Kaleidoscope (nwoeanhinnogaehr, MIT): the coordinate folded seven times around the middle.
    float t = time * 0.6;
    float2 p = uv - 0.5;
    for (int i = 0; i < 7; i++) {
        p = float2(sin(t) * p.x + cos(t) * p.y, sin(t) * p.y - cos(t) * p.x);
        t += 1.0;
        p = abs(mod(p, 2.0) - 1.0);
    }

    half4 base = content.eval(q * resolution);
    half4 fold = content.eval(p * resolution);
    float amount = 0.55 * strength * (0.5 + 0.5 * sin(time * 0.4));
    return mix(base, fold, half(amount));
}
""")
}
