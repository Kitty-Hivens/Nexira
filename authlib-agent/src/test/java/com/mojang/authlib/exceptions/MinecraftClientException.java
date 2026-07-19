package com.mojang.authlib.exceptions;

/**
 * Test stub occupying authlib's real FQN: the agent matches the rethrow site by
 * constant-pool class/method NAMES, so the fixture's catch block must reference
 * exactly this class and its {@code toAuthenticationException}.
 */
public class MinecraftClientException extends RuntimeException {

    public MinecraftClientException(String message) {
        super(message);
    }

    public RuntimeException toAuthenticationException() {
        return new IllegalStateException("rethrown: " + getMessage());
    }
}
