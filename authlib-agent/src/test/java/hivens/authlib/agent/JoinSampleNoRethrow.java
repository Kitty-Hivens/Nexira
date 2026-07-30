package hivens.authlib.agent;

import com.mojang.authlib.exceptions.MinecraftClientException;

import java.util.UUID;

/**
 * A {@code joinServer} that references {@code MinecraftClientException} (so the
 * class counts as modern authlib) but never rethrows via
 * {@code toAuthenticationException}: the shape the agent must leave untouched
 * WITH a stderr breadcrumb, because a moved joinServer means SC joins regress.
 */
public class JoinSampleNoRethrow {

    public String swallowed;

    public void joinServer(UUID profileId, String token, String serverId) {
        try {
            post();
        } catch (MinecraftClientException e) {
            swallowed = e.getMessage();
        }
    }

    private void post() {
        throw new MinecraftClientException("always");
    }
}
