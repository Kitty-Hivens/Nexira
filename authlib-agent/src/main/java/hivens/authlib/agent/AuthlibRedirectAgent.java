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
 * the join, hasJoined and profile URLs (the profile fetch is what loads skins/capes)
 * plus the texture-domain whitelist -- so request bodies stay standard Yggdrasil.
 * Deliberately ASM-free / zero-dependency so the jar is a
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
    private static final String TEXTURES_METHOD = "getTextures";

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
        // fillProfileProperties fetches skins/capes from this; miss it and the
        // profile GET goes to Mojang -> no textures -> players render as default
        // skins. The vanilla code appends "<uuid>" then "&unsigned=<bool>" via
        // concatenateURL, so the "?user=" query base yields SC's
        // auth_profile.php?user=<uuid>&unsigned=... verbatim.
        m.put("https://sessionserver.mojang.com/session/minecraft/profile/",
              "http://" + host + "/launcher/auth_profile.php?user=");
        // Texture-host whitelist: both vanilla entries -> the SC domain, so the
        // endsWith() check accepts SC-hosted skin URLs (e.g. www.smartycraft.ru).
        // SC's own patch collapses this to a single ".smartycraft.ru"; two equal
        // entries are behaviourally identical (the array length is bytecode, not a
        // constant we can resize).
        m.put(".minecraft.net", "." + baseDomain);
        m.put(".mojang.com", "." + baseDomain);
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
            boolean session = className.equals(LEGACY_SESSION);
            if (!session && !className.equals(MODERN_TEXTURES)) return null;
            try {
                byte[] out = rewriteConstants(classfileBuffer, replacements);
                // The legacy session service also rejects any texture whose property
                // is not signed by Mojang -- a signature SC cannot produce. Drop that
                // gate (force requireSecure=false in getTextures) so SC's unsigned
                // skins load. No-op on modern authlib (the pattern is absent).
                if (session) out = acceptUnsignedTextures(out);
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

    /**
     * Neutralises the secure-texture gate in the legacy
     * {@code YggdrasilMinecraftSessionService.getTextures(GameProfile, boolean)}.
     * That method, when its {@code requireSecure} argument is true (which vanilla
     * {@code NetworkPlayerInfo} passes for every other player), demands the texture
     * property carry a valid Mojang signature before it loads the skin. SC serves
     * skins unsigned, so the join works but other players render as the default
     * skin. The sole {@code iload_2} (requireSecure) that feeds the gate is rewritten
     * to {@code iconst_0}, so the following {@code ifeq} always branches past the
     * check straight to the decode + (already-repointed) whitelist step. The swap is
     * length- and stack-preserving, so no offsets, exception table, or stack-map need
     * touching. Returns [classBytes] untouched, and never throws, when the gate is
     * not patched: silently if the method is simply absent (e.g. modern authlib),
     * but with one stderr breadcrumb if the method is present yet its gate could not
     * be located -- the legacy shape changed under us, so other players' skins will
     * silently fall back to default while the join (URL rewrite) still works.
     */
    static byte[] acceptUnsignedTextures(byte[] classBytes) {
        int cpCount = u2(classBytes, 8);
        String[] utf8 = new String[cpCount];
        int p = readConstantPool(classBytes, utf8);
        p += 6; // access_flags + this_class + super_class
        int ifaces = u2(classBytes, p); p += 2 + ifaces * 2;
        int fields = u2(classBytes, p); p += 2;
        for (int f = 0; f < fields; f++) p = skipMember(classBytes, p, utf8, null);
        int methods = u2(classBytes, p); p += 2;
        int[] gate = { -1, 0 }; // [0] requireSecure gate offset; [1] set once getTextures(...Z) is seen
        for (int m = 0; m < methods && gate[0] < 0; m++) {
            p = skipMember(classBytes, p, utf8, gate);
        }
        if (gate[0] < 0) {
            if (gate[1] != 0) {
                System.err.println("[authlib-agent] legacy getTextures present but its requireSecure "
                    + "gate was not found; other players' SmartyCraft skins may not load");
            }
            return classBytes;
        }
        byte[] copy = classBytes.clone();
        copy[gate[0]] = 0x03; // iconst_0
        return copy;
    }

    /** Fills [utf8] (indexed by pool entry) and returns the offset just past the constant pool. */
    private static int readConstantPool(byte[] b, String[] utf8) {
        int cpCount = u2(b, 8);
        int p = 10; // 4 magic + 2 minor + 2 major + 2 cpCount
        int i = 1;
        while (i < cpCount) {
            int tag = b[p] & 0xFF; p += 1;
            switch (tag) {
                case 1: { int len = u2(b, p); p += 2; utf8[i] = new String(b, p, len, StandardCharsets.UTF_8); p += len; i += 1; break; }
                case 5: case 6: p += 8; i += 2; break;
                case 7: case 8: case 16: case 19: case 20: p += 2; i += 1; break;
                case 15: p += 3; i += 1; break;
                case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18: p += 4; i += 1; break;
                default: throw new IllegalStateException("unknown constant-pool tag " + tag);
            }
        }
        return p;
    }

    /**
     * Advances past one field/method member at [p], returning the next member's
     * offset. When [gateOut] is non-null and the member is the getTextures(...Z)
     * method, sets {@code gateOut[1]} to 1 (so the caller can tell "method absent"
     * from "method present, gate missing") and records the absolute offset of its
     * requireSecure iload_2 in {@code gateOut[0]}, leaving it -1 if not located.
     */
    private static int skipMember(byte[] b, int p, String[] utf8, int[] gateOut) {
        p += 2; // access_flags
        int nameIdx = u2(b, p); p += 2;
        int descIdx = u2(b, p); p += 2;
        int attrs = u2(b, p); p += 2;
        boolean target = gateOut != null
            && TEXTURES_METHOD.equals(utf8[nameIdx])
            && utf8[descIdx].endsWith("Z)Ljava/util/Map;");
        if (target) gateOut[1] = 1;
        for (int a = 0; a < attrs; a++) {
            int anIdx = u2(b, p); p += 2;
            int alen = u4(b, p); p += 4;
            if (target && gateOut[0] < 0 && "Code".equals(utf8[anIdx])) {
                int codeLen = u4(b, p + 4);       // after max_stack(2) + max_locals(2)
                gateOut[0] = findRequireSecureGate(b, p + 8, codeLen);
            }
            p += alen;
        }
        return p;
    }

    /** Offset of the {@code iload_2} immediately followed by {@code ifeq} within the code, or -1. */
    private static int findRequireSecureGate(byte[] b, int codeStart, int codeLen) {
        int pc = 0;
        while (pc < codeLen) {
            int op = b[codeStart + pc] & 0xFF;
            if (op == 0x1C && pc + 1 < codeLen && (b[codeStart + pc + 1] & 0xFF) == 0x99) {
                return codeStart + pc; // iload_2 (0x1C) immediately followed by ifeq (0x99)
            }
            pc += insnLen(b, codeStart, pc);
        }
        return -1;
    }

    /** Byte length of the JVM instruction at code-relative [pc]. */
    private static int insnLen(byte[] b, int codeStart, int pc) {
        int op = b[codeStart + pc] & 0xFF;
        switch (op) {
            case 0x10: case 0x12: case 0x15: case 0x16: case 0x17: case 0x18: case 0x19: // *push / *load <index>
            case 0x36: case 0x37: case 0x38: case 0x39: case 0x3A: case 0xA9: case 0xBC: // *store / ret / newarray
                return 2;
            case 0x11: case 0x13: case 0x14: case 0x84: // sipush / ldc_w / ldc2_w / iinc
            case 0xB2: case 0xB3: case 0xB4: case 0xB5: case 0xB6: case 0xB7: case 0xB8: // field / invoke
            case 0xBB: case 0xBD: case 0xC0: case 0xC1: case 0xC6: case 0xC7: // new / anewarray / (instance)of / ifnull(nonnull)
                return 3;
            case 0xC5: return 4; // multianewarray
            case 0xB9: case 0xBA: case 0xC8: case 0xC9: return 5; // invokeinterface/dynamic, goto_w, jsr_w
            case 0xC4: return (b[codeStart + pc + 1] & 0xFF) == 0x84 ? 6 : 4; // wide [iinc]
            case 0xAA: { // tableswitch
                int pad = (4 - ((pc + 1) & 3)) & 3;
                int tp = codeStart + pc + 1 + pad;
                int low = u4(b, tp + 4), high = u4(b, tp + 8);
                return 1 + pad + 12 + (high - low + 1) * 4;
            }
            case 0xAB: { // lookupswitch
                int pad = (4 - ((pc + 1) & 3)) & 3;
                int tp = codeStart + pc + 1 + pad;
                return 1 + pad + 8 + u4(b, tp + 4) * 8;
            }
            default:
                if (op >= 0x99 && op <= 0xA8) return 3; // if<cond> / if_<cmp> / goto / jsr
                return 1;
        }
    }

    private static int u2(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static int u4(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
             | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static void writeU2(ByteArrayOutputStream o, int v) {
        o.write((v >>> 8) & 0xFF);
        o.write(v & 0xFF);
    }
}
