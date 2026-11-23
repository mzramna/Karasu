package karasu.domain.chapter.interactor

import karasu.domain.chapter.ChapterRepository
import karasu.domain.chapter.models.ChapterUpdate
import karasu.domain.operations.OpAction
import karasu.domain.operations.OpEntity
import karasu.domain.operations.OperationLog

class UpdateChapter(
    private val chapterRepository: ChapterRepository,
    private val operations: OperationLog? = null,
) {
    suspend fun await(chapter: ChapterUpdate) = chapterRepository.update(chapter).also { record(listOf(chapter)) }

    suspend fun awaitAll(chapters: List<ChapterUpdate>) = chapterRepository.updateAll(chapters).also { record(chapters) }

    /** Only what the sync compares: the read flag and the page. A rename or a bookmark is not progress. */
    private suspend fun record(updates: List<ChapterUpdate>) {
        updates.filter { it.read != null || it.lastPageRead != null }.forEach {
            operations?.record(OpEntity.PROGRESS, OpAction.SET, chapterId = it.id)
        }
    }
}
