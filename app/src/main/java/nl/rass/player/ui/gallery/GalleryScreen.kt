package nl.rass.player.ui.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import nl.rass.player.ui.components.ChipItem
import nl.rass.player.ui.components.ChipRow
import nl.rass.player.ui.components.ContentRow
import nl.rass.player.ui.components.EmptyState
import nl.rass.player.ui.components.ErrorState
import nl.rass.player.ui.components.HeroBillboard
import nl.rass.player.ui.components.HeroInfo
import nl.rass.player.ui.components.LandscapeCard
import nl.rass.player.ui.components.LoadingShimmer
import nl.rass.player.ui.components.NavIds
import nl.rass.player.ui.components.PillButton
import nl.rass.player.ui.components.PillStyle
import nl.rass.player.ui.components.PosterCard
import nl.rass.player.ui.components.ScreenScaffold
import nl.rass.player.ui.components.SectionHeader
import nl.rass.player.ui.components.SideSheet
import nl.rass.player.ui.components.TopNavBar
import nl.rass.player.ui.components.defaultNavItems
import nl.rass.player.ui.theme.TextPrimary

private data class Fake(val id: Int, val title: String)

private val heroes = listOf(
    HeroInfo("1", "شهر گمشده", null, rating = "۷٫۸", year = "۲۰۲۴", type = "فیلم", genres = listOf("ماجراجویی", "درام"),
        description = "گروهی از کاوشگران در جست‌وجوی شهری فراموش‌شده به دل جنگل می‌روند و با رازی قدیمی روبه‌رو می‌شوند."),
    HeroInfo("2", "پاییز در تهران", null, rating = "۸٫۱", year = "۲۰۲۳", type = "سریال", genres = listOf("درام"),
        description = "داستان چند خانواده در یک محله قدیمی."),
)
private val posters = List(14) { Fake(it, "عنوان نمونه ${it + 1}") }

/** Debug-only component gallery (placeholder colour boxes, no network). */
@Composable
fun GalleryScreen() {
    var nav by remember { mutableStateOf(NavIds.Home) }
    var chip by remember { mutableStateOf("all") }
    var sheet by remember { mutableStateOf(false) }
    var heroIdx by remember { mutableStateOf(0) }
    val rowA = remember { posters }

    Box(Modifier.fillMaxSize()) {
        ScreenScaffold(topBar = { TopNavBar(defaultNavItems(), nav, { nav = it }) }) {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "hero") {
                    HeroBillboard(heroes[heroIdx], heroes.size, heroIdx, onPlay = { heroIdx = (heroIdx + 1) % heroes.size }, onMoreInfo = { sheet = true })
                }
                item(key = "chips") {
                    ChipRow(
                        listOf(ChipItem("all", "همه"), ChipItem("new", "جدیدترین"), ChipItem("top", "محبوب‌ترین"), ChipItem("year", "سال ساخت")),
                        chip, { chip = it },
                    )
                }
                item(key = "row-continue") {
                    ContentRow("ادامه تماشا", rowA.take(6), { it.id }, subtitle = "از همان‌جا که ماندی") { f, m ->
                        LandscapeCard(null, f.title, {}, m, progress = (f.id + 1) / 8f, badges = if (f.id == 1) listOf("قسمت جدید") else emptyList())
                    }
                }
                item(key = "row-top10") {
                    ContentRow("۱۰ تای برتر", rowA.take(10), { it.id }, subtitle = "پربازدیدترین‌های این هفته") { f, m ->
                        LandscapeCard(null, f.title, {}, m, rank = f.id + 1, showTitle = false)
                    }
                }
                item(key = "row-posters") {
                    ContentRow("فیلم‌های تازه", rowA, { it.id }) { f, m ->
                        PosterCard(null, f.title, {}, m, badges = if (f.id % 3 == 0) listOf("منبع ۱") else emptyList(), progress = if (f.id % 4 == 0) 0.4f else null)
                    }
                }
                item(key = "buttons") {
                    Column(Modifier.padding(vertical = 12.dp)) {
                        SectionHeader("دکمه‌ها و حالت‌ها")
                        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            PillButton("پخش", {}, style = PillStyle.Primary)
                            PillButton("اطلاعات بیشتر", {})
                            PillButton("افزودن به فهرست", {}, style = PillStyle.Accent)
                            PillButton("نمایش پنل", { sheet = true })
                        }
                        Text("در حال بارگذاری", style = MaterialTheme.typography.labelMedium, color = TextPrimary, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                        LoadingShimmer(count = 5)
                    }
                }
                item(key = "empty") { Box(Modifier.fillMaxWidth().height(220.dp)) { EmptyState(message = "فهرست شما هنوز خالی است") } }
                item(key = "error") { Box(Modifier.fillMaxWidth().height(260.dp)) { ErrorState("اتصال به منبع برقرار نشد", onRetry = {}) } }
            }
        }
        SideSheet(visible = sheet, onDismiss = { sheet = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SectionHeader("فصل‌ها")
                PillButton("فصل ۱", { sheet = false })
                PillButton("فصل ۲", { sheet = false })
            }
        }
    }
}
