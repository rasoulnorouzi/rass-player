package nl.rass.player.ui.mylist

import nl.rass.player.data.local.MyListEntity
import nl.rass.player.domain.Catalog
import nl.rass.player.domain.CatalogTitle
import nl.rass.player.domain.TitleType

/** Pure My List assembly (R3.2). */
object MyListLogic {
    /**
     * Titles of [type] in [entries], newest added first. Entries whose title is not in the catalog (source
     * disabled or still loading) are skipped but not deleted.
     */
    fun titles(entries: List<MyListEntity>, catalog: Catalog, type: TitleType): List<CatalogTitle> =
        entries.asSequence()
            .sortedWith(compareByDescending<MyListEntity> { it.addedAt }.thenBy { it.catalogKey })
            .mapNotNull { catalog.title(it.catalogKey) }
            .filter { it.type == type }
            .toList()
}
