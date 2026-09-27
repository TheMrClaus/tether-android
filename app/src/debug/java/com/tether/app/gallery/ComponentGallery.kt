package com.tether.app.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tether.app.ui.components.PerfDivider
import com.tether.app.ui.components.TetherChip
import com.tether.app.ui.components.TetherRocker
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.ThemeChoice
import com.tether.app.ui.theme.ThemeFamily
import com.tether.app.ui.theme.ThemeMode
import kotlinx.coroutines.launch

/** The font scales the gallery can force (1.3× is PLAN §4's accessibility bar). */
val GalleryFontScales: List<Float> = listOf(1f, 1.3f, 2f)

/**
 * The debug Component Gallery: every design-system primitive (by web class set, in every state),
 * the type roles, all 136 icon glyphs, the provider logos and the adaptive launcher icon, under a
 * skin switcher (family × mode — the six skins, plus `system`), a font-scale toggle and a
 * reduced-motion toggle. Sections are [GallerySections]; the screenshot tests render the same
 * section composables ([GalleryGoldens]).
 */
@Composable
fun ComponentGallery(initial: ThemeChoice = ThemeChoice(ThemeFamily.Precision, ThemeMode.System)) {
    var family by rememberSaveable { mutableStateOf(initial.family) }
    var mode by rememberSaveable { mutableStateOf(initial.mode) }
    var fontScale by rememberSaveable { mutableFloatStateOf(1f) }
    // Null: follow the device ("Remove animations"), as TetherTheme does.
    var reducedOverride by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val choice = ThemeChoice(family, mode)
    val skin = TetherSkin.of(family, mode.isDark(isSystemInDarkTheme()))

    TetherTheme(choice) {
        val t = LocalTetherTokens.current
        val reduced = reducedOverride ?: LocalReducedMotion.current
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val sections = GallerySections
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().background(t.mineral).testTag(GalleryTag),
            contentPadding = WindowInsets.safeDrawing.asPaddingValues(),
        ) {
            item(key = "controls") {
                GalleryControls(
                    skin = skin,
                    family = family,
                    mode = mode,
                    fontScale = fontScale,
                    reduced = reduced,
                    onFamily = { family = it },
                    onMode = { mode = it },
                    onFontScale = { fontScale = it },
                    onReduced = { reducedOverride = it },
                    sections = sections,
                    onJump = { index -> scope.launch { listState.scrollToItem(index + 1) } },
                )
            }
            itemsIndexed(sections, key = { _, s -> s.id }) { _, section ->
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, fontScale),
                    LocalReducedMotion provides reduced,
                ) {
                    GallerySectionBlock(section)
                }
            }
        }
    }
}

const val GalleryTag = "gallery"

/** The switcher: family, mode, font scale, reduced motion, and a jump row to every section. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GalleryControls(
    skin: TetherSkin,
    family: ThemeFamily,
    mode: ThemeMode,
    fontScale: Float,
    reduced: Boolean,
    onFamily: (ThemeFamily) -> Unit,
    onMode: (ThemeMode) -> Unit,
    onFontScale: (Float) -> Unit,
    onReduced: (Boolean) -> Unit,
    sections: List<GallerySection>,
    onJump: (Int) -> Unit,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Column(
        Modifier.fillMaxWidth().background(t.graphite).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Component Gallery", color = t.ink, style = type.screenTitle, modifier = Modifier.semantics { heading() })
        Text("skin: ${skin.id}", color = t.muted, style = type.codeBlock, modifier = Modifier.testTag(SkinLabelTag))
        ControlRow("family") {
            ThemeFamily.entries.forEach { f -> TetherChip(f.label, { onFamily(f) }, active = f == family) }
        }
        ControlRow("mode") {
            ThemeMode.entries.forEach { m -> TetherChip(m.label, { onMode(m) }, active = m == mode) }
        }
        ControlRow("font scale") {
            GalleryFontScales.forEach { s -> TetherChip("${formatScale(s)}×", { onFontScale(s) }, active = s == fontScale) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TetherRocker(checked = reduced, onCheckedChange = onReduced, contentDescription = "Reduced motion")
            Text("reduced motion", color = t.muted, style = type.chatBody)
        }
        PerfDivider()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            sections.forEachIndexed { i, s -> TetherChip(s.title, { onJump(i) }) }
        }
    }
}

const val SkinLabelTag = "gallery-skin"

private fun formatScale(s: Float): String = if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString()

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlRow(caption: String, content: @Composable () -> Unit) {
    val t = LocalTetherTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(caption, color = t.faint, style = TextStyle(fontFamily = LocalTetherTypography.current.mono, fontSize = 10.sp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
        }
    }
}

/** One gallery section: its heading and its board of states. */
class GallerySection(val id: String, val title: String, val content: @Composable ColumnScope.() -> Unit)

/** A section as the screen shows it: a micro-label heading over a padded board on `--mineral`. */
@Composable
fun GallerySectionBlock(section: GallerySection, modifier: Modifier = Modifier) {
    GalleryBoard(section.title, modifier, section.content)
}

/** The board frame every section (and every gallery golden) is drawn in. */
@Composable
fun GalleryBoard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val t = LocalTetherTokens.current
    val label = LocalTetherTypography.current.sectionLabel
    Column(
        modifier.fillMaxWidth().background(t.mineral).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(label.format(title), color = t.muted, style = label.style, modifier = Modifier.semantics { heading() })
        content()
    }
}
