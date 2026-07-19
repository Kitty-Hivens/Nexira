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
 * Modern (authlib 6.x+) join is already redirected by
 * {@code -Dminecraft.api.session.host}; there this covers what the properties
 * cannot: the texture whitelist (a hardcoded list with no system-property
 * override) and the join-response parsing, which SC's non-Yggdrasil reply
 * would otherwise abort (see {@link #tolerateJoinResponse}). Every failure is
 * swallowed -- an agent must never take the game down.
 */
public final class AuthlibRedirectAgent {

    private AuthlibRedirectAgent() {}

    // authlib classes that carry the constants we move (internal slash form).
    private static final String LEGACY_SESSION = "com/mojang/authlib/yggdrasil/YggdrasilMinecraftSessionService";
    private static final String MODERN_TEXTURES = "com/mojang/authlib/yggdrasil/TextureUrlChecker";
    private static final String TEXTURES_METHOD = "getTextures";
    private static final String JOIN_METHOD = "joinServer";
    private static final String MCE_CLASS = "com/mojang/authlib/exceptions/MinecraftClientException";
    private static final String MCE_TO_AUTH = "toAuthenticationException";

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
                if (session) {
                    out = acceptUnsignedTextures(out);
                    out = tolerateJoinResponse(out);
                }
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

    /**
     * Replicates SmartyCraft's own authlib patch for the modern (6.x+) join path.
     * SC's session endpoint answers a successful join with HTTP 200 and the profile
     * as the body, where Yggdrasil answers 204 with none; vanilla modern
     * {@code joinServer} posts expecting no payload, chokes parsing the unexpected
     * body, and rethrows -- so the join dies CLIENT-side after SC has already
     * accepted it. SC's patched jar catches that {@code MinecraftClientException}
     * and simply returns; this rewrites vanilla to the same semantics by nop-ing
     * the rethrow ({@code aload ; invokevirtual toAuthenticationException ;
     * athrow}) so the handler falls through into the {@code return} that follows.
     * SC's trade rides along: a real transport failure on join is swallowed too and
     * surfaces later as the server kicking the unverified session.
     *
     * The handler bytecode is identical across authlib 6.0.54 / 7.0.63 / 9.0.75
     * ({@code astore 5; aload 5; invokevirtual; athrow; return}). Nop-ing keeps
     * every length, so offsets, the exception table and the stack-map stay valid;
     * falling into the return meets its declared frame (empty stack, and the
     * handler's extra local verifies against the frame's implicit top padding).
     * A class with no {@code MinecraftClientException} in its pool (legacy authlib,
     * whose Gson parsing tolerates SC's body already) is returned untouched
     * silently; a modern {@code joinServer} whose shape moved leaves a stderr
     * breadcrumb instead of a patch.
     */
    static byte[] tolerateJoinResponse(byte[] classBytes) {
        int cpCount = u2(classBytes, 8);
        String[] utf8 = new String[cpCount];
        int[] tags = new int[cpCount];
        int[] refA = new int[cpCount];
        int[] refB = new int[cpCount];
        int p = readConstantPool(classBytes, utf8, tags, refA, refB);
        boolean modern = false;
        for (int i = 1; i < cpCount && !modern; i++) modern = MCE_CLASS.equals(utf8[i]);
        if (!modern) return classBytes;
        p += 6; // access_flags + this_class + super_class
        int ifaces = u2(classBytes, p); p += 2 + ifaces * 2;
        int fields = u2(classBytes, p); p += 2;
        for (int f = 0; f < fields; f++) p = skipMember(classBytes, p, utf8, null);
        int methods = u2(classBytes, p); p += 2;
        boolean joinSeen = false;
        for (int m = 0; m < methods; m++) {
            p += 2; // access_flags
            int nameIdx = u2(classBytes, p); p += 2;
            int descIdx = u2(classBytes, p); p += 2;
            int attrs = u2(classBytes, p); p += 2;
            boolean target = JOIN_METHOD.equals(utf8[nameIdx]) && utf8[descIdx].endsWith(")V");
            if (target) joinSeen = true;
            for (int a = 0; a < attrs; a++) {
                int anIdx = u2(classBytes, p); p += 2;
                int alen = u4(classBytes, p); p += 4;
                if (target && "Code".equals(utf8[anIdx])) {
                    int at = findRethrowSite(classBytes, p + 8, u4(classBytes, p + 4), utf8, tags, refA, refB);
                    if (at >= 0) {
                        byte[] copy = classBytes.clone();
                        int aloadLen = (copy[at] & 0xFF) == 0x19 ? 2 : 1;
                        for (int i = at; i < at + aloadLen + 4; i++) copy[i] = 0x00; // nop over aload + invokevirtual + athrow
                        return copy;
                    }
                }
                p += alen;
            }
        }
        if (joinSeen) {
            System.err.println("[authlib-agent] modern joinServer present but its rethrow was not found; "
                + "a non-Yggdrasil join response will still abort the join");
        }
        return classBytes;
    }

    /**
     * Absolute offset of the {@code aload} that starts the
     * {@code aload ; invokevirtual MinecraftClientException.toAuthenticationException ;
     * athrow ; return} sequence within the code, or -1. The trailing {@code return}
     * is part of the match: it is what the nop-ed handler falls into, so its
     * presence (with its existing stack-map frame) is what makes the patch safe.
     */
    private static int findRethrowSite(byte[] b, int codeStart, int codeLen,
                                       String[] utf8, int[] tags, int[] refA, int[] refB) {
        int pc = 0, prevPc = -1;
        while (pc < codeLen) {
            int op = b[codeStart + pc] & 0xFF;
            if (op == 0xB6 && prevPc >= 0 && pc + 4 < codeLen
                    && (b[codeStart + pc + 3] & 0xFF) == 0xBF   // athrow
                    && (b[codeStart + pc + 4] & 0xFF) == 0xB1) { // return it falls into
                int prevOp = b[codeStart + prevPc] & 0xFF;
                boolean prevIsAload = prevOp == 0x19 || (prevOp >= 0x2A && prevOp <= 0x2D);
                if (prevIsAload && isMceToAuthRef(u2(b, codeStart + pc + 1), utf8, tags, refA, refB)) {
                    return codeStart + prevPc;
                }
            }
            prevPc = pc;
            pc += insnLen(b, codeStart, pc);
        }
        return -1;
    }

    /** Whether pool entry [idx] is a Methodref to {@code MinecraftClientException.toAuthenticationException}. */
    private static boolean isMceToAuthRef(int idx, String[] utf8, int[] tags, int[] refA, int[] refB) {
        if (idx <= 0 || idx >= tags.length || tags[idx] != 10) return false;
        int classIdx = refA[idx];
        int natIdx = refB[idx];
        if (classIdx <= 0 || classIdx >= tags.length || tags[classIdx] != 7) return false;
        if (natIdx <= 0 || natIdx >= tags.length || tags[natIdx] != 12) return false;
        return MCE_CLASS.equals(utf8[refA[classIdx]]) && MCE_TO_AUTH.equals(utf8[refA[natIdx]]);
    }

    /** Fills [utf8] (indexed by pool entry) and returns the offset just past the constant pool. */
    private static int readConstantPool(byte[] b, String[] utf8) {
        int n = utf8.length;
        return readConstantPool(b, utf8, new int[n], new int[n], new int[n]);
    }

    /**
     * As {@link #readConstantPool(byte[], String[])}, additionally recording each
     * entry's tag and its two u2 operands ([refA]/[refB]: class + name-and-type for
     * a Methodref, name + descriptor for a NameAndType, name for a Class), which is
     * what {@link #tolerateJoinResponse} needs to resolve an invokevirtual operand
     * back to a class/method name pair.
     */
    private static int readConstantPool(byte[] b, String[] utf8, int[] tags, int[] refA, int[] refB) {
        int cpCount = u2(b, 8);
        int p = 10; // 4 magic + 2 minor + 2 major + 2 cpCount
        int i = 1;
        while (i < cpCount) {
            int tag = b[p] & 0xFF; p += 1;
            tags[i] = tag;
            switch (tag) {
                case 1: { int len = u2(b, p); p += 2; utf8[i] = new String(b, p, len, StandardCharsets.UTF_8); p += len; i += 1; break; }
                case 5: case 6: p += 8; i += 2; break;
                case 7: case 8: case 16: case 19: case 20: refA[i] = u2(b, p); p += 2; i += 1; break;
                case 15: p += 3; i += 1; break;
                case 9: case 10: case 11: case 12: case 17: case 18:
                    refA[i] = u2(b, p); refB[i] = u2(b, p + 2); p += 4; i += 1; break;
                case 3: case 4: p += 4; i += 1; break;
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
