package hivens.authlib.agent;

import java.util.HashMap;
import java.util.Map;

/**
 * Test fixture mirroring the legacy {@code getTextures} secure gate: an INSTANCE
 * method whose {@code requireSecure} argument (local slot 2 -> {@code iload_2})
 * feeds an {@code ifeq} and throws when true, exactly the shape the agent rewrites
 * ({@code iload_2 -> iconst_0}) so the gate is always skipped. The descriptor
 * matches the real method's erased form {@code (...;Z)Ljava/util/Map;}.
 */
public class TextureSample {
    public Map<String, String> getTextures(Object profile, boolean requireSecure) {
        if (requireSecure) {
            throw new IllegalStateException("secure required");
        }
        Map<String, String> out = new HashMap<String, String>();
        out.put("SKIN", "ok");
        return out;
    }
}
