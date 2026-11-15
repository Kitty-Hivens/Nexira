package hivens.widget.loader

import java.net.URL
import java.util.Collections
import java.util.Enumeration

/**
 * What a widget module's jar sees of the application it is loaded into: the parent of
 * its class loader.
 *
 * The JDK comes from the platform class loader. Of the application's own classes only
 * those in [packages] (and the packages below them) and those named in [classes] are
 * answered, from [host], so a jar shares one copy of each with the launcher. [classes]
 * is for an artifact that shares its packages with code that is not shared, the design
 * system beside the launcher's own interface: it is shared class by class, from the
 * list its build writes (see [SharedClassList]). Every other name is not found here,
 * and the jar's loader then looks in the jar itself: a class the launcher carries and
 * does not share is either the jar's own copy or missing. Resources follow the same
 * rule by the package their path names, so a service file or a class file of the
 * launcher is not read through the jar either.
 */
internal class ModuleClassView(
    name: String,
    private val host: ClassLoader,
    private val packages: Set<String>,
    private val classes: Set<String> = emptySet(),
) : ClassLoader(name, getPlatformClassLoader()) {

    override fun findClass(name: String): Class<*> {
        if (!sharesClass(name)) throw ClassNotFoundException(name)
        return host.loadClass(name)
    }

    override fun findResource(name: String): URL? = if (sharesResource(name)) host.getResource(name) else null

    override fun findResources(name: String): Enumeration<URL> =
        if (sharesResource(name)) host.getResources(name) else Collections.emptyEnumeration()

    private fun sharesClass(name: String): Boolean =
        name in classes || shares(name.substringBeforeLast('.', missingDelimiterValue = ""))

    private fun sharesResource(path: String): Boolean {
        val clean = path.trimStart('/')
        if (clean.endsWith(".class") && clean.removeSuffix(".class").replace('/', '.') in classes) return true
        return shares(clean.substringBeforeLast('/', missingDelimiterValue = "").replace('/', '.'))
    }

    /** Whether [pkg] is one of [packages] or lies below one. */
    fun shares(pkg: String): Boolean = packages.any { pkg == it || pkg.startsWith("$it.") }
}
