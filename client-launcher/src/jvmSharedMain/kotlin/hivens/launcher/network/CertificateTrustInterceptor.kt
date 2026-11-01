package hivens.launcher.network

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Turns a refused certificate into a question for the user, from wherever the
 * request came from.
 *
 * Installed on the direct channel, which is the one every call takes until a bypass
 * exists, so the roster read, the news read and the login attempt all reach it. The
 * failure is re-thrown untouched: this only reports, and the caller still fails the
 * way it always did until the user has answered.
 *
 * Only [bypassHost] is reported. The bypass exists for that one host because its
 * certificate is known to be broken; a CDN or the mirror failing the same way is a
 * real problem, and offering to trust it anyway would turn a signal into a habit.
 */
class CertificateTrustInterceptor(
    private val gate: CertificateTrustGate,
    private val bypassHost: () -> String,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return try {
            chain.proceed(request)
        } catch (e: IOException) {
            if (request.url.host.equals(bypassHost(), ignoreCase = true) && e.isCertificateFailure()) {
                gate.request(request.url.host)
            }
            throw e
        }
    }
}

/**
 * Whether [this] is a TLS trust failure rather than any other IO problem. The
 * certificate exception is usually a cause rather than the thrown type, so the chain
 * is walked -- bounded, since a cycle in a cause chain is not this file's problem to
 * hang on.
 */
private fun Throwable.isCertificateFailure(): Boolean =
    generateSequence(this) { it.cause.takeIf { cause -> cause !== it } }
        .take(8)
        .any { it is SSLHandshakeException || it is SSLPeerUnverifiedException || it is CertificateException }
