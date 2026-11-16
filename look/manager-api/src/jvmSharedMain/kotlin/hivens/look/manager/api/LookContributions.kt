package hivens.look.manager.api

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import hivens.widget.api.ModuleContribution
import hivens.widget.model.ApiVersion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.reflect.KClass

/**
 * One axis of the look, colour or motion or one a module invents: the questions its contract names, as
 * the type [A] of its answers, and the answers that hold where no provider answers ([base]).
 *
 * A contract module carries it as an object, `object Wobble : LookAxis<Wobble>("mymodule.wobble", ...)`,
 * and the manager takes it from every module brought in. The first module to bring an axis of an [id]
 * has it, and a later one bringing the same id is refused. Every provider of the axis answers in [A],
 * and a widget reads the answers through [local].
 *
 * A class rather than an interface so it can grow, and so [local] is made here and nowhere else: the
 * manager provides the answers under the axis's own local and nothing more, so an axis cannot reach
 * into what the host provides around it.
 *
 * Carried like any other contribution: a jar lists it as a service under this class's name,
 * `META-INF/services/hivens.look.manager.api.LookAxis`, and names [LookApi.ID] in its manifest.
 */
abstract class LookAxis<A : Any>(
    /** The axis's id, which packs and the person's choice name it by. */
    final override val id: String,
    /**
     * The version of the axis's contract. A provider says which it was built against
     * ([LookProvider.contract]), and one this version does not serve is refused.
     */
    val contract: ApiVersion,
    /** The type of the answers, which every provider's answers are held to. */
    val type: KClass<A>,
    /** The answers where no provider answers: neutral, and complete for every question of the axis. */
    val base: A,
) : ModuleContribution {

    /**
     * Where a widget reads the answers, [base] outside any look. A change of one answer recomposes only
     * what reads it.
     */
    val local: ProvidableCompositionLocal<A> = compositionLocalOf { base }
}

/**
 * Well known optional features a provider may declare in [LookProvider.capabilities]. A provider may
 * declare others, and the manager answers for any of them alike.
 */
object LookCapability {
    /** It answers in a dark and a light variant, so choosing between them is offered ([LookChoice.variant]). */
    const val VARIANTS: String = "variants"

    /** It draws with a blur that can be switched off, so a switch for it is offered ([LookChoice.blur]). */
    const val BLUR: String = "blur"

    /** Other modules may extend it, through a contribution its own API declares, a theme say. */
    const val EXTENSIBLE: String = "extensible"
}

/**
 * One way to answer an axis: the answers of the axis [axis] in the family [family], which a pack or the
 * person's choice names it by.
 *
 * The manager opens a provider only while the person's choice, or a subtree's, needs it ([open]), off
 * the thread that asked, and closes what it opened when that stops. Two providers of one family for
 * one axis are never both used: the first module to bring one has it.
 *
 * Carried like any other contribution: a jar lists it as a service under this interface's name,
 * `META-INF/services/hivens.look.manager.api.LookProvider`, and names [LookApi.ID] in its manifest.
 */
interface LookProvider<A : Any> : ModuleContribution {
    /**
     * Its id among its module's contributions, which [ModuleContribution] asks of every one, made of its
     * axis and family. What a pack and the choice name it by is [family].
     */
    override val id: String get() = "$axis:$family"

    /** The id of the axis it answers. */
    val axis: String

    /** The family it belongs to, `nx` or `material`, which a pack names it by for its axis. */
    val family: String

    /** The version of the axis's contract it was built against. One the axis's version does not serve is refused. */
    val contract: ApiVersion

    /** The optional features it has, see [LookCapability]. A setting for one is offered only where the provider in use declares it. */
    val capabilities: Set<String> get() = emptySet()

    /**
     * Its answers, ready at once and as they change, until they are closed. Called only when the choice
     * needs it, and never on the thread that draws. Whatever it throws, a cancellation of its own
     * included, or answers that are not of the axis's type, cost only this provider: the axis goes on
     * down the choice as if the provider were not there.
     */
    fun open(context: LookContext): LookAnswers<A>
}

/** What the manager hands a provider it opens. It grows with what the manager answers, so a provider never implements it. */
interface LookContext {
    /** Cancelled when the manager closes the provider's answers, so the work the provider started stops with them. Runs off the drawing thread. */
    val coroutineScope: CoroutineScope

    /**
     * The variant the person chose, for a provider that declares [LookCapability.VARIANTS], and null where
     * the person left it to the provider. Always null for a provider that does not declare it.
     */
    val variant: StateFlow<LookVariant?>

    /**
     * Whether the person wants the blur, for a provider that declares [LookCapability.BLUR], and null where
     * the person left it to the provider. Always null for a provider that does not declare it.
     */
    val blur: StateFlow<Boolean?>
}

/** A provider's answers while it is open. */
interface LookAnswers<out A> : AutoCloseable {
    /** The answers now, and every time they change. */
    val answers: StateFlow<A>

    /** Lets go of what the answers hold. Nothing by default. */
    override fun close() {}

    companion object {
        /** Answers that are [value] for as long as they are open. */
        fun <A> of(value: A): LookAnswers<A> = following(MutableStateFlow(value))

        /** Answers that are what [flow] holds. */
        fun <A> following(flow: StateFlow<A>): LookAnswers<A> = object : LookAnswers<A> {
            override val answers: StateFlow<A> = flow
        }
    }
}

/**
 * A look as data: the provider family it names for each axis, under its [id]. It names providers and
 * nothing else, so any setting of one is the provider's own.
 *
 * A pack need not name every axis: one it leaves out takes the default pack's provider ([LookApi.DEFAULT_PACK]),
 * and the axis's base answers where that names none either. A family no module brings is passed over the
 * same way. The first module to bring a pack of an id has it, and the default pack's id is kept for the
 * modules compiled into the launcher, so no jar becomes everyone's default.
 *
 * Carried like any other contribution: a jar lists it as a service under this interface's name,
 * `META-INF/services/hivens.look.manager.api.LookPack`, and names [LookApi.ID] in its manifest.
 */
interface LookPack : ModuleContribution {
    /** The provider family for each axis it names, by axis id. */
    val providers: Map<String, String>
}
