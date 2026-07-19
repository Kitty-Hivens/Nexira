package hivens.authlib.agent;

import com.mojang.authlib.SignatureState;

/**
 * An {@code unpackTextures} that references {@code SignatureState.SIGNED} (so the
 * agent counts it as the modern shape) but never calls
 * {@code getPropertySignatureState} in the {@code aload_0 ; aload_1 ; invokevirtual}
 * form: the case the agent must leave untouched WITH a stderr breadcrumb, since a
 * moved call shape means SC skins would silently regress to default.
 */
public class TextureSignatureSampleNoCall {

    public SignatureState unpackTextures(Object property) {
        return SignatureState.SIGNED;
    }
}
