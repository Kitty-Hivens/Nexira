package hivens.authlib.agent;

/**
 * Test fixture: a compiled class carrying the exact vanilla authlib constant
 * strings the rewriter targets, so the test can transform its real .class bytes
 * and verify the swap + that the class still loads/verifies.
 */
public class Sample {
    public static final String JOIN = "https://sessionserver.mojang.com/session/minecraft/join";
    public static final String HAS_JOINED = "https://sessionserver.mojang.com/session/minecraft/hasJoined";
    public static final String PROFILE = "https://sessionserver.mojang.com/session/minecraft/profile/";
    public static final String[] DOMAINS = { ".minecraft.net", ".mojang.com" };
    // A non-target authlib URL (the login server, handled by the launcher, never
    // redirected by the agent) that must survive untouched.
    public static final String KEEP = "https://authserver.mojang.com/authenticate";
}
