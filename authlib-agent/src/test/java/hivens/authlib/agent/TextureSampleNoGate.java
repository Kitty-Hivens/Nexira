package hivens.authlib.agent;

import java.util.HashMap;
import java.util.Map;

/**
 * Test fixture for a reshaped legacy method: a {@code getTextures(...;Z)Ljava/util/Map;}
 * whose body never reads {@code requireSecure}, so javac emits no {@code iload_2 ; ifeq}
 * gate. Stands in for a future authlib whose method is present but no longer matches the
 * agent's pattern -- the agent must SEE the method (and warn) yet leave the bytes
 * untouched, because there is no gate to flip.
 */
public class TextureSampleNoGate {
    public Map<String, String> getTextures(Object profile, boolean requireSecure) {
        Map<String, String> out = new HashMap<String, String>();
        out.put("SKIN", "ok");
        return out;
    }
}
