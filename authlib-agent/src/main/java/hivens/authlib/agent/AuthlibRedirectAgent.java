package hivens.authlib.agent;

import java.io.ByteArrayOutputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.Map;

/**
 * A -javaagent that redirects the game's authlib at class-load to SmartyCraft's
 * endpoints, so an SC-bound pack joins (and skins validate) WITHOUT shipping or
 * swapping SC's patched authlib jar.
 *
 * authlib is a library loaded early, which an agent's {@link ClassFileTransformer}
 * can rewrite before the class is used -- something a Forge coremod / Mixin cannot
 * reliably do (the same reason the ecosystem's third-party-auth tool,
 * authlib-injector, is also an agent). This rewrites only constant-pool strings --
 * the join + hasJoined URLs and the texture-domain whitelist -- so request bodies
 * stay standard Yggdrasil. Deliberately ASM-free / zero-dependency so the jar is a
 * plain Java 8 agent that loads in any game JVM (legacy 1.7.10 / 1.12.2 .. 1.21).
 *
 * Modern (authlib 6.x) join is already redirected by
 * {@code -Dminecraft.api.session.host}; there only the texture whitelist (a
 * hardcoded list with no system-property override) needs this. Every failure is
 * swallowed -- an agent must never take the game down.
 */
public final class AuthlibRedirectAgent {

    private AuthlibRedirectAgent() {}

    // authlib classes that carry the constants we move (internal slash form).
    private static final String LEGACY_SESSION = "com/mojang/authlib/yggdrasil/YggdrasilMinecraftSessionService";
    private static final String MODERN_TEXTURES = "com/mojang/authlib/yggdrasil/TextureUrlChecker";

    private static final String DEFAULT_HOST = "www.smartycraft.ru";

    /** JVM agent entry point (Premain-Class in the jar manifest). */
    public static void premain(String agentArgs, Instrumentation inst) {
        try {
            inst.addTransformer(new Redirector(buildReplacements(parseHost(agentArgs))));
        } catch (Throwable ignored) {
            // an agent must never break the launch
        }
    }

    /** agentArgs is {@code host=<sc-host>} (or a bare host); blank -> the default. */
    static String parseHost(String agentArgs) {
        if (agentArgs == null) return DEFAULT_HOST;
        String a = agentArgs.trim();
        int eq = a.indexOf('=');
        String v = (eq >= 0 ? a.substring(eq + 1) : a).trim();
        return v.isEmpty() ? DEFAULT_HOST : v;
    }

    /**
     * The exact vanilla-authlib constant strings mapped to their SmartyCraft
     * equivalents for [host]. Values pre-encoded to UTF-8 for the rewriter.
     */
    static Map<String, byte[]> buildReplacements(String host) {
        String baseDomain = host.startsWith("www.") ? host.substring(4) : host;
        Map<String, String> m = new HashMap<String, String>();
        m.put("https://sessionserver.mojang.com/session/minecraft/join",
              "http://" + host + "/launcher/auth_joinserver.php");
        m.put("https://sessionserver.mojang.com/session/minecraft/hasJoined",
              "http://" + host + "/launcher/auth_has_joined.php");
        m.put(".minecraft.net", "." + baseDomain);
        m.put(".mojang.com", ".www." + baseDomain);
        Map<String, byte[]> out = new HashMap<String, byte[]>();
        for (Map.Entry<String, String> e : m.entrySet()) {
            out.put(e.getKey(), e.getValue().getBytes(StandardCharsets.UTF_8));
        }
        return out;
    }

    private static final class Redirector implements ClassFileTransformer {
        private final Map<String, byte[]> replacements;

        Redirector(Map<String, byte[]> replacements) { this.replacements = replacements; }

        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            if (className == null) return null;
            if (!className.equals(LEGACY_SESSION) && !className.equals(MODERN_TEXTURES)) return null;
            try {
                byte[] out = rewriteConstants(classfileBuffer, replacements);
                return out == classfileBuffer ? null : out;
            } catch (Throwable ignored) {
                return null; // leave the original class on any trouble
            }
        }
    }

    /**
     * Replaces matching {@code CONSTANT_Utf8} entries in [classBytes] with the
     * mapped UTF-8 bytes; non-matching entries are copied byte-for-byte (so
     * modified-UTF-8 entries are never re-encoded), and everything after the
     * constant pool is copied verbatim -- the class references constants by index,
     * not file offset, so re-lengthing a Utf8 entry is safe. Returns the input
     * array unchanged when nothing matched.
     */
    static byte[] rewriteConstants(byte[] classBytes, Map<String, byte[]> replacements) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(classBytes.length + 64);
        out.write(classBytes, 0, 8); // magic(4) + minor(2) + major(2)
        int p = 8;
        int cpCount = u2(classBytes, p); p += 2;
        writeU2(out, cpCount);

        boolean changed = false;
        int i = 1;
        while (i < cpCount) {
            int tag = classBytes[p] & 0xFF; p += 1;
            out.write(tag);
            switch (tag) {
                case 1: { // Utf8
                    int len = u2(classBytes, p); p += 2;
                    byte[] rep = replacements.get(new String(classBytes, p, len, StandardCharsets.UTF_8));
                    if (rep != null) {
                        writeU2(out, rep.length); out.write(rep, 0, rep.length);
                        changed = true;
                    } else {
                        writeU2(out, len); out.write(classBytes, p, len);
                    }
                    p += len; i += 1; break;
                }
                case 5: case 6: // Long / Double: 8 bytes AND take two pool slots
                    out.write(classBytes, p, 8); p += 8; i += 2; break;
                case 7: case 8: case 16: case 19: case 20: // Class/String/MethodType/Module/Package
                    out.write(classBytes, p, 2); p += 2; i += 1; break;
                case 15: // MethodHandle
                    out.write(classBytes, p, 3); p += 3; i += 1; break;
                case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18:
                    out.write(classBytes, p, 4); p += 4; i += 1; break;
                default:
                    throw new IllegalStateException("unknown constant-pool tag " + tag);
            }
        }
        if (!changed) return classBytes;
        out.write(classBytes, p, classBytes.length - p); // body after the constant pool, verbatim
        return out.toByteArray();
    }

    private static int u2(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static void writeU2(ByteArrayOutputStream o, int v) {
        o.write((v >>> 8) & 0xFF);
        o.write(v & 0xFF);
    }
}
