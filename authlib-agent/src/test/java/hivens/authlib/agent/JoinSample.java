package hivens.authlib.agent;

import com.mojang.authlib.exceptions.MinecraftClientException;

import java.util.UUID;

/**
 * Fixture mirroring modern authlib's {@code joinServer}: the catch block loads
 * the exception, calls {@code toAuthenticationException} and throws the result,
 * with the method's {@code return} directly after -- the exact instruction
 * sequence {@link AuthlibRedirectAgent#tolerateJoinResponse} nops so the
 * handler falls through into that return.
 */
public class JoinSample {

    /** Makes {@code post()} fail like SC's 200-with-body join response does. */
    public static boolean failPost;

    public void joinServer(UUID profileId, String token, String serverId) {
        try {
            post();
        } catch (MinecraftClientException e) {
            throw e.toAuthenticationException();
        }
    }

    private void post() {
        if (failPost) throw new MinecraftClientException("Failed to read value {\"id\":\"...\"}");
    }
}
