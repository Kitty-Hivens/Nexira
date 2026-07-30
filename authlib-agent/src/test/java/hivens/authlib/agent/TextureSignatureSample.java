package hivens.authlib.agent;

import com.mojang.authlib.SignatureState;

/**
 * Fixture mirroring modern authlib's {@code unpackTextures}: it opens by storing
 * {@code getPropertySignatureState(property)} into a local, exactly the
 * {@code aload_0 ; aload_1 ; invokevirtual ; astore} sequence
 * {@link AuthlibRedirectAgent#forceSignedTextures} rewrites to a constant
 * {@code SignatureState.SIGNED}. {@link #alwaysSigned()} keeps a SIGNED field
 * reference in the pool the way real authlib does, so the agent can find it.
 */
public class TextureSignatureSample {

    /** Stands in for SC's dummy-signed property -> vanilla verdict is INVALID. */
    public SignatureState unpackTextures(Object property) {
        SignatureState state = getPropertySignatureState(property);
        return state;
    }

    private SignatureState getPropertySignatureState(Object property) {
        return SignatureState.INVALID;
    }

    public SignatureState alwaysSigned() {
        return SignatureState.SIGNED;
    }
}
