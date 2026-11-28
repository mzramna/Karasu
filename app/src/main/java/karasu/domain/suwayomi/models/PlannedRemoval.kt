package karasu.domain.suwayomi.models

import kotlinx.serialization.Serializable

/** What kind of thing a sync would remove. */
enum class RemovalKind {
    /** A downloaded chapter (the file), here or on the server's other devices. */
    DOWNLOADED_CHAPTER,

    /** A work taken out of the library. */
    LIBRARY_WORK,

    /** An extension repository. */
    EXTENSION_REPO,

    /** A source taken out of a work's merged sources. */
    MERGED_SOURCE,

    /** A chapter taken out of the reading history. */
    HISTORY_ENTRY,

    /** A work taken out of a category. */
    CATEGORY_LINK,
}

/** Where the removal would happen: on this device (a sync being received) or on the server (a sync being sent). */
enum class RemovalSide { THIS_DEVICE, SERVER }

/**
 * A removal the sync found and did not do because the user has not approved it yet. [key] identifies the thing
 * stably, so an approval given for this list lets exactly these removals through on the next run and nothing else.
 * [label] is what the confirmation shows, in plain words.
 */
@Serializable
data class PlannedRemoval(
    val kind: RemovalKind,
    val side: RemovalSide,
    val key: String,
    val label: String,
)

/** The removals of one kind on one side, with a few of their labels, for the confirmation to show. */
data class RemovalGroup(
    val side: RemovalSide,
    val kind: RemovalKind,
    val count: Int,
    val examples: List<String>,
)

/** Groups [removals] by side and kind, this device first, keeping at most [examples] labels of each group. */
fun groupRemovals(removals: List<PlannedRemoval>, examples: Int = 3): List<RemovalGroup> =
    removals.groupBy { it.side to it.kind }
        .map { (group, list) -> RemovalGroup(group.first, group.second, list.size, list.take(examples).map { it.label }) }
        .sortedWith(compareBy({ it.side.ordinal }, { it.kind.ordinal }))
