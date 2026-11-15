package hivens.ui.components

import androidx.compose.runtime.Composable
import hivens.accounts.api.AccountKeys
import hivens.widget.api.rememberScopedAction
import hivens.widget.api.rememberScopedCommand
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull

/**
 * Asks for the code a launch waits on, as the accounts module puts the question.
 *
 * MUST be composed inside [hivens.ui.theme.NxTheme]: the prompt is a layer of the
 * window that takes its theme from where it is opened, and one raised from outside the
 * theme finds no `LocalScheme` and takes the shell down with it.
 *
 * Signing the account in for the launch, saving what comes of it and handing the
 * session back to the launch are the module's. This draws the question and fires the
 * answer, and draws nothing where no accounts module is brought in.
 */
@Composable
fun TwoFactorPromptHost() {
    val question = rememberScopedSource(AccountKeys.SecondFactor).valueOrNull() ?: return
    val answer = rememberScopedCommand(AccountKeys.AnswerSecondFactor) ?: return
    val dismiss = rememberScopedAction(AccountKeys.DismissSecondFactor) ?: return

    ConfirmCodeDialog(
        onDismiss = dismiss,
        onSubmit = answer,
        errorMessage = question.error,
        isSubmitting = question.checking,
        puppetPrefix = "launch.twoFactor",
    )
}
