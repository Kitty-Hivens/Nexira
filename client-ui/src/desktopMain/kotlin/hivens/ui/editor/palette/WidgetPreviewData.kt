package hivens.ui.editor.palette

import hivens.core.activity.Activity
import hivens.core.activity.ActivityAction
import hivens.core.activity.ActivityKind
import hivens.core.activity.ActivityPhase
import hivens.ui.notifications.Kind
import hivens.ui.notifications.NotifGlyph
import hivens.ui.notifications.PersistedNotification
import hivens.ui.notifications.Severity
import hivens.ui.screens.mod.OpenProject
import hivens.ui.screens.mod.ProjectCreator
import hivens.ui.screens.mod.ProjectLink
import hivens.ui.screens.mod.ProjectLinkKind
import hivens.ui.screens.mod.ProjectSource
import hivens.ui.widgets.Sources
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.flowSource
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Stand-in data for the gallery, so a widget that draws a list has a list to draw.
 *
 * Several widgets compose perfectly well and put nothing on screen, because what
 * they show is what is happening and in the gallery nothing is: an activity feed
 * with no activity, a notification archive with no notifications, a project rail
 * with no project open. The tile then falls back to a letter and the reader
 * learns nothing about a widget that works.
 *
 * This is the same answer Android arrived at with a preview layout: the picture
 * shows the widget full rather than the widget as it happens to be. The
 * difference is where the content comes from. Android has the author draw a
 * separate layout; these are the real widgets reading real sources, with the
 * sources substituted, so there is still exactly one render path and a preview
 * cannot drift from what the launcher draws.
 *
 * Substituted wholesale rather than layered over the live registry. A gallery
 * should show what a widget is for, not what the reader's launcher is doing this
 * second, and a tile that is empty because nothing is installed is the bug this
 * exists to fix.
 *
 * Names here are roles rather than people, and the project is openly invented.
 */
internal fun previewDataRegistry(): WidgetDataRegistry = WidgetDataRegistry().apply {
    register(Sources.Activity, flowSource(MutableStateFlow(SAMPLE_ACTIVITY)))
    register(Sources.Notifications, flowSource(MutableStateFlow(SAMPLE_NOTIFICATIONS)))
    register(Sources.DoNotDisturb, flowSource(MutableStateFlow(false)))
    register(Sources.OpenProject, flowSource(MutableStateFlow<OpenProject?>(SAMPLE_PROJECT)))
}

// Read per call rather than once per class load, which is what a `val` here did:
// the first palette of the session froze the clock, and a launcher left open for
// an afternoon showed a sample notification from three hours ago.
private val now: Long get() = System.currentTimeMillis()
private const val MINUTE = 60_000L

/**
 * One of each phase a feed can show, because a feed that is all progress bars
 * demonstrates a third of the widget.
 */
private val SAMPLE_ACTIVITY: List<Activity> get() = listOf(
    Activity(
        key = "preview:install",
        kind = ActivityKind.Install,
        title = "Industrial",
        phase = ActivityPhase.Running(done = 62, total = 100, detail = "assets"),
        startedAtMillis = now - MINUTE,
        updatedAtMillis = now,
        actions = setOf(ActivityAction.Cancel),
    ),
    Activity(
        key = "preview:update",
        kind = ActivityKind.Update,
        title = "Nevermine",
        phase = ActivityPhase.Succeeded,
        startedAtMillis = now - 8 * MINUTE,
        updatedAtMillis = now - 6 * MINUTE,
        actions = setOf(ActivityAction.Dismiss),
    ),
)

private val SAMPLE_NOTIFICATIONS: List<PersistedNotification> get() = listOf(
    PersistedNotification(
        sourceKey = "preview:update",
        sender = "Industrial",
        glyph = NotifGlyph.Update,
        severity = Severity.Info,
        kind = Kind.ActionRequired,
        title = "A newer build is available",
        createdAtEpoch = (now - 3 * MINUTE) / 1000,
    ),
    PersistedNotification(
        sourceKey = "preview:done",
        sender = "Nevermine",
        severity = Severity.Success,
        kind = Kind.OneShot,
        title = "Installed",
        body = "Ready to play",
        createdAtEpoch = (now - 40 * MINUTE) / 1000,
    ),
)

/**
 * A project with every block filled, so the five rail widgets each have something
 * to draw. Invented rather than borrowed: a gallery is shipped, and a real
 * project's name, people and licence are not ours to put in one.
 */
private val SAMPLE_PROJECT = OpenProject(
    targetKey = "preview:project",
    title = "Sample Mod",
    slug = "sample-mod",
    source = ProjectSource.Catalogue,
    gameVersionLabels = listOf("1.20.1", "1.21 - 1.21.4"),
    loaders = listOf("fabric", "neoforge"),
    categories = listOf("technology", "storage", "utility"),
    clientSide = "optional",
    serverSide = "required",
    licenseId = "MIT",
    licenseName = "MIT License",
    publishedAt = "2 years ago",
    updatedAt = "last week",
    publishedExact = "12 March 2024",
    updatedExact = "14 September 2026",
    links = listOf(
        ProjectLink(ProjectLinkKind.Source, "https://example.invalid/source"),
        ProjectLink(ProjectLinkKind.Issues, "https://example.invalid/issues"),
        ProjectLink(ProjectLinkKind.Wiki, "https://example.invalid/wiki"),
    ),
    creators = listOf(
        ProjectCreator(name = "Author", role = "Owner", owner = true),
        ProjectCreator(name = "Contributor", role = "Developer"),
    ),
    authors = listOf("Author"),
    dependencies = listOf("sample-library"),
    sizeBytes = 2_400_000,
)
