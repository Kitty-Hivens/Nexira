package hivens.authlib.agent;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

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
        assertEquals(".smartycraft.ru", str(r.get(".minecraft.net")));
        assertEquals(".www.smartycraft.ru", str(r.get(".mojang.com")));
    }

    @Test
    void rewriteSwapsConstantsAndClassStillLoads() throws Exception {
        byte[] original = readClassBytes(Sample.class);
        Map<String, byte[]> map = AuthlibRedirectAgent.buildReplacements("www.smartycraft.ru");

        byte[] rewritten = AuthlibRedirectAgent.rewriteConstants(original, map);

        // Define the transformed bytes under the same FQN in an isolated loader so
        // it does not clash with the already-loaded fixture; reading the fields
        // proves the rewrite produced a valid, loadable class.
        Class<?> swapped = new IsolatedLoader(rewritten).load("hivens.authlib.agent.Sample");
        assertEquals("http://www.smartycraft.ru/launcher/auth_joinserver.php", field(swapped, "JOIN"));
        assertEquals("http://www.smartycraft.ru/launcher/auth_has_joined.php", field(swapped, "HAS_JOINED"));
        String[] domains = (String[]) swapped.getField("DOMAINS").get(null);
        assertEquals(".smartycraft.ru", domains[0]);
        assertEquals(".www.smartycraft.ru", domains[1]);
        // Non-target constant survives.
        assertEquals("https://sessionserver.mojang.com/session/minecraft/", field(swapped, "KEEP"));
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

    /** Defines exactly one class (the rewritten fixture) from bytes; delegates the rest. */
    private static final class IsolatedLoader extends ClassLoader {
        private final byte[] bytes;

        IsolatedLoader(byte[] bytes) {
            super(AuthlibRedirectAgentTest.class.getClassLoader());
            this.bytes = bytes;
        }

        Class<?> load(String name) throws ClassNotFoundException {
            return loadClass(name, true);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if ("hivens.authlib.agent.Sample".equals(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) c = defineClass(name, bytes, 0, bytes.length);
                if (resolve) resolveClass(c);
                return c;
            }
            return super.loadClass(name, resolve);
        }
    }
}
