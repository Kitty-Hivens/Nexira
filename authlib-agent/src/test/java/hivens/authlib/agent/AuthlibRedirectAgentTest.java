package hivens.authlib.agent;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class AuthlibRedirectAgentTest {

    @Test
    void parseHostDefaultsAndStrips() {
        assertEquals("www.smartycraft.ru", AuthlibRedirectAgent.parseHost(null));
        assertEquals("www.smartycraft.ru", AuthlibRedirectAgent.parseHost("   "));
        assertEquals("mirror.example", AuthlibRedirectAgent.parseHost("host=mirror.example"));
        assertEquals("mirror.example", AuthlibRedirectAgent.parseHost("mirror.example"));
    }

    @Test
    void buildReplacementsMapsUrlsAndDomains() {
        Map<String, byte[]> r = AuthlibRedirectAgent.buildReplacements("www.smartycraft.ru");
        assertEquals("http://www.smartycraft.ru/launcher/auth_joinserver.php",
                str(r.get("https://sessionserver.mojang.com/session/minecraft/join")));
        assertEquals("http://www.smartycraft.ru/launcher/auth_has_joined.php",
                str(r.get("https://sessionserver.mojang.com/session/minecraft/hasJoined")));
        assertEquals("http://www.smartycraft.ru/launcher/auth_profile.php?user=",
                str(r.get("https://sessionserver.mojang.com/session/minecraft/profile/")));
        assertEquals(".smartycraft.ru", str(r.get(".minecraft.net")));
        assertEquals(".smartycraft.ru", str(r.get(".mojang.com")));
    }

    @Test
    void rewriteSwapsConstantsAndClassStillLoads() throws Exception {
        byte[] original = readClassBytes(Sample.class);
        Map<String, byte[]> map = AuthlibRedirectAgent.buildReplacements("www.smartycraft.ru");

        byte[] rewritten = AuthlibRedirectAgent.rewriteConstants(original, map);

        // Define the transformed bytes under the same FQN in an isolated loader so
        // it does not clash with the already-loaded fixture; reading the fields
        // proves the rewrite produced a valid, loadable class.
        Class<?> swapped = new IsolatedLoader("hivens.authlib.agent.Sample", rewritten).load("hivens.authlib.agent.Sample");
        assertEquals("http://www.smartycraft.ru/launcher/auth_joinserver.php", field(swapped, "JOIN"));
        assertEquals("http://www.smartycraft.ru/launcher/auth_has_joined.php", field(swapped, "HAS_JOINED"));
        assertEquals("http://www.smartycraft.ru/launcher/auth_profile.php?user=", field(swapped, "PROFILE"));
        String[] domains = (String[]) swapped.getField("DOMAINS").get(null);
        assertEquals(".smartycraft.ru", domains[0]);
        assertEquals(".smartycraft.ru", domains[1]);
        // Non-target constant survives.
        assertEquals("https://authserver.mojang.com/authenticate", field(swapped, "KEEP"));
    }

    @Test
    void textureDomainsWarnWhenTheCheckerHoldsNoneOfThem() throws Exception {
        // authlib 7.0.63 replaced the two suffix constants with a single host compared
        // for equality, so the swap finds nothing and the whitelist keeps pointing at
        // Mojang. Skins then fail while the join keeps working -- undiagnosable from
        // inside the game unless the agent says so.
        Map<String, byte[]> map = AuthlibRedirectAgent.buildReplacements("www.smartycraft.ru");
        byte[] modern = readClassBytes(TextureCheckerModernSample.class);
        byte[][] out = new byte[1][];
        String warned = captureErr(() -> out[0] = AuthlibRedirectAgent.redirectTextureDomains(modern, map));

        assertSame(modern, out[0], "nothing matched, so the class must come back untouched");
        assertTrue(warned.contains("[authlib-agent]") && warned.contains("default skin"),
                "a checker the agent cannot repoint must leave a breadcrumb: " + warned);
    }

    @Test
    void textureDomainsStaySilentWhenTheSwapLands() throws Exception {
        // The legacy shape still carries both constants: repointed, and nothing to say.
        Map<String, byte[]> map = AuthlibRedirectAgent.buildReplacements("www.smartycraft.ru");
        byte[] legacy = readClassBytes(Sample.class);
        byte[][] out = new byte[1][];
        String quiet = captureErr(() -> out[0] = AuthlibRedirectAgent.redirectTextureDomains(legacy, map));

        assertNotSame(legacy, out[0], "the legacy constants must still be swapped");
        assertEquals("", quiet, "a successful repoint must not warn");
    }

    @Test
    void acceptUnsignedTexturesDropsTheSecureGate() throws Exception {
        byte[] original = readClassBytes(TextureSample.class);
        byte[] patched = AuthlibRedirectAgent.acceptUnsignedTextures(original);
        assertNotSame(original, patched, "the iload_2 ; ifeq gate must be found and patched");

        // Patched: getTextures(..., requireSecure=true) no longer throws -> returns the texture.
        Class<?> c = new IsolatedLoader("hivens.authlib.agent.TextureSample", patched)
                .load("hivens.authlib.agent.TextureSample");
        Object instance = c.getDeclaredConstructor().newInstance();
        Object result = c.getMethod("getTextures", Object.class, boolean.class).invoke(instance, null, true);
        assertEquals("ok", ((Map<?, ?>) result).get("SKIN"),
                "with requireSecure forced false the texture is returned, not rejected");

        // Control: the unpatched fixture still throws on requireSecure=true.
        try {
            new TextureSample().getTextures(null, true);
            fail("unpatched getTextures must throw when requireSecure is true");
        } catch (IllegalStateException expected) {
            // the gate is intact without the patch
        }
    }

    @Test
    void acceptUnsignedTexturesNoOpWhenGateAbsent() {
        // Sample has no getTextures(...Z) method -> the array is returned unchanged.
        byte[] original = readClassBytes(Sample.class);
        assertSame(original, AuthlibRedirectAgent.acceptUnsignedTextures(original));
    }

    @Test
    void acceptUnsignedTexturesWarnsOnlyWhenGetTexturesPresentButGateMissing() throws Exception {
        // getTextures(...Z) is present but never branches on requireSecure, so there is
        // no gate to flip: the bytes come back unchanged AND a breadcrumb is logged,
        // because a present-but-unpatchable legacy method means the authlib shape moved
        // under us and other players' skins will silently regress.
        byte[] reshaped = readClassBytes(TextureSampleNoGate.class);
        byte[][] out = new byte[1][];
        String warned = captureErr(() -> out[0] = AuthlibRedirectAgent.acceptUnsignedTextures(reshaped));
        assertSame(reshaped, out[0], "a getTextures with no requireSecure gate must be left untouched");
        assertTrue(warned.contains("[authlib-agent]") && warned.contains("skins"),
                "a present-but-unpatchable getTextures must leave a stderr breadcrumb: " + warned);

        // getTextures(...Z) absent entirely (e.g. modern authlib): no-op AND silent.
        byte[] absent = readClassBytes(Sample.class);
        String quiet = captureErr(() -> AuthlibRedirectAgent.acceptUnsignedTextures(absent));
        assertEquals("", quiet, "an absent getTextures must not warn");
    }

    @Test
    void tolerateJoinSwallowsTheRethrow() throws Exception {
        byte[] original = readClassBytes(JoinSample.class);
        byte[] patched = AuthlibRedirectAgent.tolerateJoinResponse(original);
        assertNotSame(original, patched, "the rethrow site must be found and nop-ed");

        // Patched: a post() failure no longer aborts the join. defineClass runs
        // full verification, so this also proves the nop-ed handler satisfies
        // the method's existing stack-map.
        Class<?> c = new IsolatedLoader("hivens.authlib.agent.JoinSample", patched)
                .load("hivens.authlib.agent.JoinSample");
        Object join = c.getDeclaredConstructor().newInstance();
        c.getField("failPost").setBoolean(null, true);
        try {
            c.getMethod("joinServer", java.util.UUID.class, String.class, String.class)
                    .invoke(join, java.util.UUID.randomUUID(), "token", "server-id");
        } finally {
            c.getField("failPost").setBoolean(null, false);
        }

        // Control: the unpatched fixture still rethrows.
        JoinSample.failPost = true;
        try {
            new JoinSample().joinServer(java.util.UUID.randomUUID(), "token", "server-id");
            fail("unpatched joinServer must rethrow");
        } catch (IllegalStateException expected) {
            // the rethrow is intact without the patch
        } finally {
            JoinSample.failPost = false;
        }
    }

    @Test
    void tolerateJoinSilentNoOpWithoutModernMarker() throws Exception {
        // No MinecraftClientException in the pool = legacy authlib, whose join
        // parsing tolerates SC's body already: untouched AND quiet.
        byte[] original = readClassBytes(Sample.class);
        byte[][] out = new byte[1][];
        String quiet = captureErr(() -> out[0] = AuthlibRedirectAgent.tolerateJoinResponse(original));
        assertSame(original, out[0], "a legacy-shaped class must pass through unchanged");
        assertEquals("", quiet, "a legacy-shaped class must not warn");
    }

    @Test
    void tolerateJoinWarnsWhenModernJoinShapeMoved() throws Exception {
        // References MinecraftClientException and has a joinServer, but not the
        // rethrow sequence: untouched, WITH a breadcrumb -- a moved modern shape
        // means SC joins will start dying client-side again.
        byte[] reshaped = readClassBytes(JoinSampleNoRethrow.class);
        byte[][] out = new byte[1][];
        String warned = captureErr(() -> out[0] = AuthlibRedirectAgent.tolerateJoinResponse(reshaped));
        assertSame(reshaped, out[0], "an unmatchable joinServer must be left untouched");
        assertTrue(warned.contains("[authlib-agent]") && warned.contains("join"),
                "a present-but-unpatchable joinServer must leave a stderr breadcrumb: " + warned);
    }

    @Test
    void forceSignedTexturesFlipsTheVerdictToSigned() throws Exception {
        byte[] original = readClassBytes(TextureSignatureSample.class);
        byte[] patched = AuthlibRedirectAgent.forceSignedTextures(original);
        assertNotSame(original, patched, "the getPropertySignatureState call must be found and rewritten");

        // Patched: unpackTextures reports SIGNED instead of INVALID. defineClass
        // runs full verification, so this also proves the rewritten sequence
        // still satisfies the method's stack-map.
        Class<?> c = new IsolatedLoader("hivens.authlib.agent.TextureSignatureSample", patched)
                .load("hivens.authlib.agent.TextureSignatureSample");
        Object sample = c.getDeclaredConstructor().newInstance();
        Object state = c.getMethod("unpackTextures", Object.class).invoke(sample, new Object());
        assertEquals("SIGNED", state.toString(),
                "with the call rewritten to a constant, unpackTextures reports SIGNED");

        // Control: the unpatched fixture still reports INVALID.
        assertEquals(com.mojang.authlib.SignatureState.INVALID,
                new TextureSignatureSample().unpackTextures(new Object()));
    }

    @Test
    void forceSignedTexturesSilentNoOpWithoutSignatureState() throws Exception {
        // No SignatureState.SIGNED in the pool = legacy authlib: untouched AND quiet.
        byte[] original = readClassBytes(Sample.class);
        byte[][] out = new byte[1][];
        String quiet = captureErr(() -> out[0] = AuthlibRedirectAgent.forceSignedTextures(original));
        assertSame(original, out[0], "a legacy-shaped class must pass through unchanged");
        assertEquals("", quiet, "a legacy-shaped class must not warn");
    }

    @Test
    void forceSignedTexturesWarnsWhenUnpackShapeMoved() throws Exception {
        // References SignatureState and has unpackTextures, but not the
        // getPropertySignatureState call sequence: untouched, WITH a breadcrumb.
        byte[] reshaped = readClassBytes(TextureSignatureSampleNoCall.class);
        byte[][] out = new byte[1][];
        String warned = captureErr(() -> out[0] = AuthlibRedirectAgent.forceSignedTextures(reshaped));
        assertSame(reshaped, out[0], "an unmatchable unpackTextures must be left untouched");
        assertTrue(warned.contains("[authlib-agent]") && warned.contains("skins"),
                "a present-but-unpatchable unpackTextures must leave a stderr breadcrumb: " + warned);
    }

    @Test
    void rewriteReturnsSameArrayWhenNothingMatches() {
        // Empty map -> no entry can match -> the rewriter must return the input
        // array untouched (the no-op fast path).
        byte[] original = readClassBytes(Sample.class);
        byte[] out = AuthlibRedirectAgent.rewriteConstants(original, java.util.Collections.<String, byte[]>emptyMap());
        assertSame(original, out, "no matching constants -> the input array is returned unchanged");
    }

    private static String str(byte[] b) {
        assertNotNull(b);
        return new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Runs [body] with System.err captured and returns everything it printed. */
    private static String captureErr(Runnable body) throws Exception {
        PrintStream original = System.err;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buf, true, "UTF-8"));
        try {
            body.run();
        } finally {
            System.setErr(original);
        }
        return buf.toString("UTF-8");
    }

    private static String field(Class<?> c, String name) throws Exception {
        return (String) c.getField(name).get(null);
    }

    private static byte[] readClassBytes(Class<?> c) {
        String res = "/" + c.getName().replace('.', '/') + ".class";
        try (InputStream in = c.getResourceAsStream(res)) {
            assertNotNull(in, "fixture class bytes not found on the test classpath: " + res);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Defines exactly one named class (the rewritten fixture) from bytes; delegates the rest. */
    private static final class IsolatedLoader extends ClassLoader {
        private final String target;
        private final byte[] bytes;

        IsolatedLoader(String target, byte[] bytes) {
            super(AuthlibRedirectAgentTest.class.getClassLoader());
            this.target = target;
            this.bytes = bytes;
        }

        Class<?> load(String name) throws ClassNotFoundException {
            return loadClass(name, true);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (target.equals(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) c = defineClass(name, bytes, 0, bytes.length);
                if (resolve) resolveClass(c);
                return c;
            }
            return super.loadClass(name, resolve);
        }
    }
}
