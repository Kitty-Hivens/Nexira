package hivens.launcher.smoke

import org.junit.jupiter.api.Tag

/**
 * Marks a test as a *live smoke* — touches the real `smartycraft.ru` API
 * over the production proxy. Excluded from the regular `:client-launcher:test`
 * task; included only by the dedicated `:client-launcher:smokeTest` task,
 * which is gated behind the `SMARTY_TEST_USER` / `SMARTY_TEST_PASS`
 * GitHub Secrets.
 *
 * JUnit Jupiter propagates `@Tag` through composed annotations, so
 * tagging a class with `@SmokeTest` is equivalent to `@Tag("smoke")` on
 * every test method inside it.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Tag("smoke")
annotation class SmokeTest
