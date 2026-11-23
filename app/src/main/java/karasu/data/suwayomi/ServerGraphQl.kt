package karasu.data.suwayomi

import karasu.domain.suwayomi.models.LenientIdSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The few GraphQL operations of Suwayomi's own `/api/graphql` that the sync needs, written out by
 * hand. Field and argument names follow the server's schema (as used by Suwayomi-Bunko's
 * `Category.graphql`, `Chapter.graphql` and `Manga.graphql`); there is deliberately no codegen.
 */
internal object ServerGraphQl {

    const val CATEGORIES = """
        query KarasuCategories {
          categories(orderBy: ORDER, orderByType: ASC) {
            nodes { id name order default }
          }
        }
    """

    const val CREATE_CATEGORY = """
        mutation KarasuCreateCategory(${'$'}name: String!) {
          createCategory(input: {name: ${'$'}name}) {
            category { id name order default }
          }
        }
    """

    const val UPDATE_MANGA_CATEGORIES = """
        mutation KarasuUpdateMangaCategories(${'$'}id: Int!, ${'$'}add: [Int!], ${'$'}remove: [Int!]) {
          updateMangaCategories(
            input: {id: ${'$'}id, patch: {addToCategories: ${'$'}add, removeFromCategories: ${'$'}remove}}
          ) {
            clientMutationId
          }
        }
    """

    /**
     * The server's library with the two things the sync compares: category links and per-chapter
     * read state. Paged because a chapter list is the heavy part and a large library would
     * otherwise be one enormous response.
     */
    const val LIBRARY = """
        query KarasuLibrary(${'$'}first: Int!, ${'$'}offset: Int!) {
          mangas(
            filter: {inLibrary: {equalTo: true}}
            orderBy: ID
            orderByType: ASC
            first: ${'$'}first
            offset: ${'$'}offset
          ) {
            nodes {
              id
              sourceId
              url
              categories { nodes { id } }
              chapters { nodes { id url isRead lastPageRead } }
            }
            pageInfo { hasNextPage }
          }
        }
    """

    /**
     * Builds one request that updates many chapters, each with its own patch.
     *
     * `updateChapters` applies a single patch to a list of ids, which is no use when every
     * chapter has its own `lastPageRead`; aliased `updateChapter` calls in one document are.
     */
    fun updateChaptersDocument(count: Int): String = buildString {
        append("mutation KarasuUpdateChapters(")
        repeat(count) { append("\$id$it: Int!, \$patch$it: UpdateChapterPatchInput!") ; if (it < count - 1) append(", ") }
        append(") {\n")
        repeat(count) { append("  c$it: updateChapter(input: {id: \$id$it, patch: \$patch$it}) { clientMutationId }\n") }
        append("}")
    }
}

@Serializable
data class RemoteCategory(
    val id: Int,
    val name: String,
    val order: Int = 0,
    /** The server's built-in "Default" category; it is not a real group and is never synced. */
    @SerialName("default") val isDefault: Boolean = false,
)

@Serializable
data class RemoteChapter(
    val id: Int,
    val url: String,
    val isRead: Boolean = false,
    val lastPageRead: Int = 0,
)

/** A manga of the server's library, identified for matching by [sourceId] + [url], never by [id]. */
data class RemoteManga(
    val id: Int,
    val sourceId: String,
    val url: String,
    val categoryIds: Set<Int>,
    val chapters: List<RemoteChapter>,
)

@Serializable
internal data class Nodes<T>(val nodes: List<T> = emptyList())

@Serializable
internal data class CategoriesData(val categories: Nodes<RemoteCategory>)

@Serializable
internal data class CreateCategoryData(val createCategory: CreatedCategory) {
    @Serializable
    data class CreatedCategory(val category: RemoteCategory)
}

@Serializable
internal data class LibraryData(val mangas: MangaPage)

@Serializable
internal data class MangaPage(val nodes: List<MangaNode> = emptyList(), val pageInfo: PageInfo = PageInfo())

@Serializable
internal data class PageInfo(val hasNextPage: Boolean = false)

@Serializable
internal data class MangaNode(
    val id: Int,
    @Serializable(with = LenientIdSerializer::class) val sourceId: String,
    val url: String,
    val categories: Nodes<CategoryId> = Nodes(),
    val chapters: Nodes<RemoteChapter> = Nodes(),
) {
    @Serializable
    data class CategoryId(val id: Int)

    fun toRemote() = RemoteManga(
        id = id,
        sourceId = sourceId,
        url = url,
        categoryIds = categories.nodes.mapTo(mutableSetOf()) { it.id },
        chapters = chapters.nodes,
    )
}
