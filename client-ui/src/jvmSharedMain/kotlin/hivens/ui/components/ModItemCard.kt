package hivens.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hivens.core.data.OptionalMod
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.theme.Motion
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxColor
import hivens.ui.theme.Status

/**
 * Improved mod item card with description preview, category badges,
 * conflict warnings, and file list.
 */
@Composable
fun ModItemCard(
    mod: OptionalMod,
    isChecked: Boolean,
    onToggle: (Boolean) -> Unit,
    enabledModIds: Set<String> = emptySet()
) {
    val s = LocalStrings.current
    var expanded by remember { mutableStateOf(false) }
    val hasDescription = !mod.description.isNullOrBlank()
    val conflictingIds = mod.incompatibleIds.filter { it in enabledModIds && it != mod.id }
    val hasConflicts = conflictingIds.isNotEmpty()

    val backgroundColor by animateColorAsState(
        when {
            hasConflicts && isChecked -> NxColor.status(Status.Error).copy(alpha = 0.08f)
            isChecked -> NxColor.lead().copy(alpha = 0.12f)
            else -> NxColor.page.copy(alpha = 0.25f)
        }, Motion.colorShift.of()
    )
    val borderColor by animateColorAsState(
        when {
            hasConflicts && isChecked -> NxColor.status(Status.Error).copy(alpha = 0.4f)
            isChecked -> NxColor.lead().copy(alpha = 0.4f)
            else -> NxInk.line.copy(alpha = 0.12f)
        }, Motion.colorShift.of()
    )

    Column(
        Modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(backgroundColor)
            .border(1.dp, borderColor, MaterialTheme.shapes.medium)
            .clickable { onToggle(!isChecked) }
            .padding(12.dp)
            .animateContentSize(animationSpec = Motion.reveal.of())
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Checkbox(
                checked = isChecked, onCheckedChange = null,
                colors = CheckboxDefaults.colors(
                    checkedColor = if (hasConflicts) NxColor.status(Status.Error) else NxColor.lead(),
                    uncheckedColor = NxInk.quiet.copy(alpha = 0.4f)
                )
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(mod.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = NxInk.main, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (!mod.category.isNullOrBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.clip(MaterialTheme.shapes.extraSmall).background(NxColor.lead().copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                            Text(mod.category!!, fontSize = 10.sp, color = NxColor.lead(), fontWeight = FontWeight.Bold)
                        }
                    }
                }
                if (hasDescription && !expanded) {
                    Spacer(Modifier.height(2.dp))
                    Text(mod.description!!.lines().firstOrNull()?.take(120) ?: "", style = MaterialTheme.typography.bodySmall, color = NxInk.quiet.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (hasDescription || mod.jars.size > 1 || mod.incompatibleIds.isNotEmpty()) {
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(28.dp)) {
                    Symbol(if (expanded) NxIcon.ExpandLess else NxIcon.ExpandMore, null, tint = if (expanded) NxColor.lead() else NxInk.quiet.copy(alpha = 0.45f), modifier = Modifier.size(20.dp))
                }
            }
        }

        if (expanded) {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = NxInk.line.copy(alpha = 0.15f))
            Spacer(Modifier.height(8.dp))
            Column(Modifier.padding(start = 40.dp)) {
                if (hasDescription) {
                    Text(mod.description!!, style = MaterialTheme.typography.bodySmall, color = NxInk.quiet, lineHeight = MaterialTheme.typography.bodySmall.fontSize * 1.5)
                    Spacer(Modifier.height(8.dp))
                }
                if (mod.jars.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Symbol(NxIcon.FolderOpen, null, tint = NxInk.quiet.copy(alpha = 0.4f), modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(mod.jars.joinToString(", "), style = MaterialTheme.typography.labelSmall, color = NxInk.quiet.copy(alpha = 0.5f))
                    }
                }
                if (hasConflicts) {
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(NxColor.status(Status.Error).copy(alpha = 0.08f)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Symbol(NxIcon.Warning, null, tint = NxColor.status(Status.Error), modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(s.modConflictWarning(conflictingIds.joinToString(", ")), style = MaterialTheme.typography.labelSmall, color = NxColor.status(Status.Error))
                    }
                } else if (mod.incompatibleIds.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(s.modIncompatibleHint(mod.incompatibleIds.joinToString(", ")), style = MaterialTheme.typography.labelSmall, color = NxInk.quiet.copy(alpha = 0.4f))
                }
            }
        }
    }
}
