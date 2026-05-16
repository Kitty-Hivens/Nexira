package hivens.ui.logging

import ch.qos.logback.classic.pattern.ThrowableProxyConverter
import ch.qos.logback.classic.spi.ILoggingEvent
import hivens.core.logging.Redactor

/**
 * Companion to [RedactingMessageConverter] for the THROWABLE half of a log event.
 * Logback's standard `%ex` / `%xEx` print stack traces straight from
 * `Throwable.toString()` -- message text included -- so exceptions like
 * `IOException("GET /auth?accessToken=xxx returned 500")` would leak the token
 * to disk even though the surrounding message went through `%rmsg`.
 *
 * Logback's PatternLayout auto-appends a default ThrowableProxyConverter when
 * the pattern doesn't include any throwable converter; including `%rex` in our
 * patterns suppresses that auto-append because this class extends
 * [ThrowableProxyConverter] (which extends ThrowableHandlingConverter, the
 * marker logback looks for).
 */
class RedactingThrowableConverter : ThrowableProxyConverter() {
    override fun convert(event: ILoggingEvent): String =
        Redactor.redact(super.convert(event))
}
