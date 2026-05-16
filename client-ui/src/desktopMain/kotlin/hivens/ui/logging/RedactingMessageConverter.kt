package hivens.ui.logging

import ch.qos.logback.classic.pattern.MessageConverter
import ch.qos.logback.classic.spi.ILoggingEvent
import hivens.core.logging.Redactor

/**
 * Custom logback conversion word `%rmsg` -- wraps the standard `%msg` and runs
 * the formatted message through [Redactor] before it lands in any appender.
 *
 * Registered in `logback.xml` as:
 *   <conversionRule conversionWord="rmsg"
 *                   converterClass="hivens.ui.logging.RedactingMessageConverter"/>
 *
 * Means tokens / passwords / UUIDs are scrubbed BEFORE they hit disk, not at
 * read time -- so if the launcher is killed mid-write we still don't leave
 * raw credentials in launcher.log / network.log / game.log / crash.log.
 */
class RedactingMessageConverter : MessageConverter() {
    override fun convert(event: ILoggingEvent): String =
        Redactor.redact(super.convert(event))
}
