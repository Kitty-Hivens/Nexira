package com.mojang.authlib;

/**
 * Test stub occupying authlib's real FQN so the agent, which matches the
 * signature-state field by constant-pool class + name, resolves the fixture's
 * {@code SignatureState.SIGNED} exactly as it would the game's.
 */
public enum SignatureState {
    SIGNED,
    UNSIGNED,
    INVALID,
}
