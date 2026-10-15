package nl.rass.player.ui.mylist

import kotlin.test.Test
import kotlin.test.assertEquals
import nl.rass.player.data.local.MyListEntity
import nl.rass.player.domain.Catalog
import nl.rass.player.domain.CatalogTitle
import nl.rass.player.domain.TitleMetadata
import nl.rass.player.domain.TitleType

class MyListLogicTest {
    private fun t(key: String, type: TitleType) = CatalogTitle(key, type, TitleMetadata(titleEn = key), sources = emptyList())

    @Test
    fun `newest first, split by type, missing titles skipped`() {
        val catalog = Catalog(titles = listOf(t("m1", TitleType.MOVIE), t("m2", TitleType.MOVIE), t("s1", TitleType.SERIES)))
        val entries = listOf(
            MyListEntity("m1", "movie", 100), MyListEntity("gone", "movie", 300),
            MyListEntity("m2", "movie", 200), MyListEntity("s1", "series", 50),
        )
        assertEquals(listOf("m2", "m1"), MyListLogic.titles(entries, catalog, TitleType.MOVIE).map { it.key })
        assertEquals(listOf("s1"), MyListLogic.titles(entries, catalog, TitleType.SERIES).map { it.key })
    }
}
