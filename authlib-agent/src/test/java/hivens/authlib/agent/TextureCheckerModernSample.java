package hivens.authlib.agent;

import java.util.HashSet;
import java.util.Set;

/**
 * Test fixture mirroring {@code TextureUrlChecker} as authlib 7.0.63+ ships it:
 * one allowed host, compared whole for equality, and none of the suffix constants
 * the agent knows how to move.
 *
 * Taken from the real class -- 7.0.63 and 9.0.75 both disassemble to
 * {@code ALLOWED_DOMAINS = Set.of("textures.minecraft.net")} with
 * {@code ALLOWED_DOMAINS.contains(host)} as the verdict, where 7.0.61 still had
 * {@code ".minecraft.net"} / {@code ".mojang.com"} and matched by suffix.
 */
public class TextureCheckerModernSample {

    public static final Set<String> ALLOWED_DOMAINS = new HashSet<String>();

    static {
        ALLOWED_DOMAINS.add("textures.minecraft.net");
    }

    public static boolean isAllowedTextureDomain(String host) {
        return ALLOWED_DOMAINS.contains(host);
    }
}
