package hivens.launcher.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmArgPolicyTest {

    private fun allowed(vararg args: String) = args.filter { JvmArgPolicy.allows(it) }

    /**
     * The reason the policy is shaped by value and not by name: which collector
     * suits a machine is the owner's call, not the launcher's, and there are
     * dozens of tuning knobs behind that choice.
     */
    @Test
    fun `garbage collector choice and heap tuning pass whole`() {
        val tuning = arrayOf(
            "-XX:+UseZGC", "-XX:+ZGenerational", "-XX:+UseG1GC", "-XX:+UseShenandoahGC",
            "-XX:+UseParallelGC", "-XX:MaxGCPauseMillis=50", "-XX:G1HeapRegionSize=16m",
            "-XX:MaxRAMPercentage=75", "-XX:ZCollectionInterval=5.0", "-XX:ActiveProcessorCount=4",
            "-XX:+UseStringDeduplication", "-XX:+AlwaysPreTouch", "-XX:-UseBiasedLocking",
            "-Xmx8G", "-Xms512m", "-Xss2m",
        )
        assertEquals(tuning.toList(), allowed(*tuning))
    }

    /** The flags mods actually ask for are all system properties. */
    @Test
    fun `mod properties pass`() {
        val modFlags = arrayOf(
            "-Dcustomskinloader.ignorePatchFailure=true",
            "-Dmixin.env.disableRefMap=true",
            "-Dforge.logging.console.level=debug",
            "-Djava.net.preferIPv4Stack=true",
            "-Dmixin.debug",
        )
        assertEquals(modFlags.toList(), allowed(*modFlags))
    }

    /**
     * None of these is named in the policy. They are refused because a path, a
     * class or a command is not a number -- which is what keeps the rule correct
     * against a JDK that has not shipped yet.
     */
    @Test
    fun `a flag whose value is a path or a command is refused without being named`() {
        val hostile = arrayOf(
            "-XX:OnError=/tmp/run.sh",
            "-XX:OnOutOfMemoryError=/bin/sh",
            "-XX:VMOptionsFile=/tmp/opts",
            "-XX:Flags=/tmp/flags",
            "-XX:SharedArchiveFile=/tmp/app.jsa",
            "-XX:CompileCommandFile=/tmp/cc",
        )
        hostile.forEach { assertFalse(JvmArgPolicy.allows(it), it) }
        hostile.forEach { name -> assertTrue(JvmArgPolicy.DENIED_FLAGS.none { name.contains(it) }, name) }
    }

    @Test
    fun `anything that names something to load is refused`() {
        listOf(
            "-javaagent:/tmp/cheat.jar",
            "-agentlib:jdwp=transport=dt_socket",
            "-agentpath:/tmp/cheat.so",
            "-Xbootclasspath/a:/tmp/boot.jar",
            "-Xrunjdwp:transport=dt_socket",
            "--patch-module=java.base=/tmp/p.jar",
            "--module-path=/tmp/mods",
            "--add-modules=ALL-DEFAULT",
            "-cp", "/tmp/evil.jar",
            "-classpath", "/tmp/evil.jar",
        ).forEach { assertFalse(JvmArgPolicy.allows(it), it) }
    }

    /** A property that reads like a setting and is in fact a class loader. */
    @Test
    fun `the property denylist covers the ones that resolve to a class or a path`() {
        listOf(
            "-Dfml.coreMods.load=com.example.Core",
            "-Djava.system.class.loader=com.example.Loader",
            "-Djava.library.path=/tmp/natives",
            "-Djava.class.path=/tmp/evil.jar",
            "-Djava.util.logging.config.class=com.example.Cfg",
            "-Djdk.attach.allowAttachSelf=true",
        ).forEach { assertFalse(JvmArgPolicy.allows(it), it) }
    }

    /** The launcher's own decisions are not a launch's to reverse. */
    @Test
    fun `a launch cannot turn the attach mechanism back on`() {
        assertFalse(JvmArgPolicy.allows("-XX:-DisableAttachMechanism"))
        assertFalse(JvmArgPolicy.allows("-XX:+DisableAttachMechanism"))
    }

    @Test
    fun `filter splits, keeps and reports what it refused`() {
        val result = JvmArgPolicy.filter("-Xmx6G  -javaagent:/tmp/x.jar -XX:+UseZGC -Dfml.coreMods.load=X")

        assertEquals(listOf("-Xmx6G", "-XX:+UseZGC"), result.kept)
        assertEquals(listOf("-javaagent:/tmp/x.jar", "-Dfml.coreMods.load=X"), result.refused)
    }

    @Test
    fun `blank input yields nothing rather than an empty argument`() {
        assertEquals(emptyList(), JvmArgPolicy.filter(null).kept)
        assertEquals(emptyList(), JvmArgPolicy.filter("   ").kept)
        assertEquals(emptyList(), JvmArgPolicy.filter("   ").refused)
    }
}
