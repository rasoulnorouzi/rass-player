package nl.rass.player.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import nl.rass.player.R
import nl.rass.player.domain.filter.ContentFilterConfig
import nl.rass.player.domain.filter.ContentWarning
import nl.rass.player.ui.format.fa

/** R13.3 «فیلتر محتوا». Every change is written at once; the catalog rebuilds from the settings flow. */
@Composable
fun ContentFilterPage(onBack: () -> Unit, vm: PrefsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsState()
    val title = stringResource(R.string.set_sec_content_filter)
    val cur = s ?: return SettingsPage(title, onBack) {}
    val f = cur.contentFilter
    fun change(t: (ContentFilterConfig) -> ContentFilterConfig) = vm.update { it.copy(contentFilter = t(it.contentFilter)) }
    var adding by remember { mutableStateOf(false) }

    val ages = listOf(0, 7, 13, 16, 18, ContentFilterConfig.MAX_AGE_NONE).map {
        it to if (it == ContentFilterConfig.MAX_AGE_NONE) stringResource(R.string.cf_age_none)
        else stringResource(R.string.cf_age_value, it.fa())
    }
    val labels = mapOf(
        ContentWarning.SEXUAL to R.string.cf_w_sexual, ContentWarning.NUDITY to R.string.cf_w_nudity,
        ContentWarning.GORE to R.string.cf_w_gore, ContentWarning.DISTURBING to R.string.cf_w_disturbing,
        ContentWarning.VIOLENCE to R.string.cf_w_violence, ContentWarning.HORROR to R.string.cf_w_horror,
        ContentWarning.DRUGS to R.string.cf_w_drugs, ContentWarning.LANGUAGE to R.string.cf_w_language,
    )
    SettingsPage(title, onBack) {
        item { SwitchRow(stringResource(R.string.cf_enabled), f.enabled, { v -> change { it.copy(enabled = v) } }, subtitle = stringResource(R.string.cf_enabled_hint)) }
        item { SwitchRow(stringResource(R.string.cf_show_home), f.showOnHome, { v -> change { it.copy(showOnHome = v) } }, subtitle = stringResource(R.string.cf_show_home_hint), enabled = f.enabled && !f.hideEverywhere) }
        item { SwitchRow(stringResource(R.string.cf_hide_all), f.hideEverywhere, { v -> change { it.copy(hideEverywhere = v) } }, subtitle = stringResource(R.string.cf_hide_all_hint), enabled = f.enabled) }
        item { ChoiceRow(stringResource(R.string.cf_max_age), ages, f.maxAge, { a -> change { it.copy(maxAge = a) } }) }
        item { StatusText(stringResource(R.string.cf_warnings_title)) }
        ContentWarning.entries.forEach { w ->
            item(key = "w-${w.wire}") {
                CheckRow(stringResource(labels.getValue(w)), w.wire in f.blockedWarnings, { on ->
                    change { it.copy(blockedWarnings = if (on) it.blockedWarnings + w.wire else it.blockedWarnings - w.wire) }
                })
            }
        }
        item { SwitchRow(stringResource(R.string.cf_hide_unrated), f.hideUnrated, { v -> change { it.copy(hideUnrated = v) } }) }
        item { StatusText(stringResource(R.string.cf_keywords_title)) }
        f.customKeywords.forEach { k ->
            item(key = "k-$k") {
                SettingRow(k, { change { it.copy(customKeywords = it.customKeywords - k) } }, subtitle = stringResource(R.string.cf_keyword_remove_hint))
            }
        }
        item { SettingRow(stringResource(R.string.cf_keyword_add), { adding = true }) }
    }
    if (adding) TextEntryDialog(
        stringResource(R.string.cf_keyword_add), stringResource(R.string.cf_keyword_label), "",
        stringResource(R.string.set_save), stringResource(R.string.set_cancel),
        onSave = { v -> if (v.isNotBlank()) change { it.copy(customKeywords = (it.customKeywords + v.trim()).distinct()) }; adding = false },
        onDismiss = { adding = false },
    )
}
