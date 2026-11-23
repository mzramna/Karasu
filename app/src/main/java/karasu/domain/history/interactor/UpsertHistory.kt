package karasu.domain.history.interactor

import eu.kanade.tachiyomi.data.database.models.History
import karasu.domain.history.HistoryRepository
import karasu.domain.operations.OpAction
import karasu.domain.operations.OpEntity
import karasu.domain.operations.OperationLog

class UpsertHistory(
    private val historyRepository: HistoryRepository,
    private val operations: OperationLog? = null,
) {
    suspend fun await(chapterId: Long, lastRead: Long, timeRead: Long) =
        historyRepository.upsert(chapterId, lastRead, timeRead).also { record(chapterId, lastRead) }

    suspend fun await(history: History) =
        historyRepository.upsert(history.chapter_id, history.last_read, history.time_read)
            .also { record(history.chapter_id, history.last_read) }

    suspend fun awaitBulk(histories: List<History>) =
        historyRepository.bulkUpsert(histories)

    /** A last-read of 0 is how the app removes a chapter from the history. */
    private suspend fun record(chapterId: Long, lastRead: Long) {
        operations?.record(OpEntity.HISTORY, if (lastRead > 0) OpAction.SET else OpAction.REMOVE, chapterId = chapterId)
    }
}
