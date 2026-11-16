package hivens.look.manager

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import hivens.look.manager.api.LocalLookProvision
import hivens.look.manager.api.LookChoice
import hivens.look.manager.api.LookProvision

/**
 * The answers of one axis. Equal to another's for the same axis, the same load of it, and equal answers,
 * so an unchanged look is not provided again and an axis loaded again is.
 */
internal class AxisAnswer(val axis: AxisEntry, val value: Any) {

    /** The answers under the axis's own local, which the axis cannot choose: nothing of the host's is reached. */
    fun provided(): ProvidedValue<*> = axis.axis.local provides value

    override fun equals(other: Any?): Boolean = other is AxisAnswer && other.axis.axis === axis.axis && other.value == value

    override fun hashCode(): Int = 31 * System.identityHashCode(axis.axis) + value.hashCode()
}

/** [content] with [answers] provided, and [provision] for [hivens.look.manager.api.OverrideLook] to nest in where it is not null. */
@Composable
private fun ProvideAnswers(answers: List<AxisAnswer>, provision: LookProvision?, content: @Composable () -> Unit) {
    val values = remember(answers, provision) {
        answers.map(AxisAnswer::provided) + listOfNotNull(provision?.let { LocalLookProvision provides it })
    }
    CompositionLocalProvider(*values.toTypedArray(), content = content)
}

/**
 * The value of `look.provide` while the manager [owner] is up: one value for its whole life, which reads
 * the answers inside, so a change of one axis reaches only what reads that axis and the host's tree
 * around it is not drawn again. Inside a subtree's look it is the subtree's, [within] being the choice
 * the subtrees around laid over the person's, so a subtree in a subtree lays its choice over theirs.
 */
internal class LiveProvision(private val owner: LookManager, private val within: LookChoice? = null) : LookProvision {

    @Composable
    override fun Provide(content: @Composable () -> Unit) {
        val answers by owner.answers.collectAsState()
        ProvideAnswers(answers, this, content)
    }

    @Composable
    override fun Override(choice: LookChoice, content: @Composable () -> Unit) {
        val subtree = within?.overlaidWith(choice) ?: choice
        val lease = remember(owner, subtree) { owner.lease(subtree) }
        val inside = remember(owner, subtree) { LiveProvision(owner, subtree) }
        val nested by lease.answers.collectAsState()
        ProvideAnswers(nested, inside, content)
    }
}

/**
 * Answers worked out before the boot, with no manager behind them: the boot window's look. A subtree's
 * look is not resolved here, since nothing could open its providers, and the subtree keeps the boot look.
 */
internal class FixedProvision(val answers: List<AxisAnswer>) : LookProvision {

    @Composable
    override fun Provide(content: @Composable () -> Unit) = ProvideAnswers(answers, this, content)

    @Composable
    override fun Override(choice: LookChoice, content: @Composable () -> Unit) = content()
}
