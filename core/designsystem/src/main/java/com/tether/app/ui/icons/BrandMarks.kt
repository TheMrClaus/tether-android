package com.tether.app.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp

/**
 * ta-y3dw: brand marks that are not Lucide glyphs (so they stay out of [TetherIcons], whose inventory
 * is pinned to the web's Lucide imports).
 *
 * [GitHub] is GitHub's official Octicons `mark-github-16`, path data copied byte for byte.
 *  - Source: https://github.com/primer/octicons, `icons/mark-github-16.svg` (main, fetched 2026-11-05).
 *  - File SHA-256 of the fetched svg: 53bb0fb0aaf5ec59434eaad1428d030bb4fdd2756dfd3c752e54805e786980c6.
 *  - Licence: MIT, (c) 2026 GitHub Inc.; the notice ships at `core/designsystem/third_party/octicons/LICENSE`.
 *  - It sits in an 18-unit viewport with a 1-unit inset so the disc is about 11.6dp in a 13dp icon slot,
 *    optically level with a Lucide glyph. Fill is black; `Icon(tint = ...)` recolours it.
 */
object BrandMarks {
    val GitHub: ImageVector by lazy {
        ImageVector.Builder(
            name = "BrandMarks.GitHub",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 18f,
            viewportHeight = 18f,
        ).group(translationX = 1f, translationY = 1f) {
            addPath(
                pathData = PathParser().parsePathString(GITHUB_PATH).toNodes(),
                fill = SolidColor(Color.Black),
            )
        }.build()
    }

    private const val GITHUB_PATH =
        "M6.766 11.328c-2.063-.25-3.516-1.734-3.516-3.656 0-.781.281-1.625.75-2.188-.203-.515-.172-1.609.063-2.062.625-.078 1.468.25 1.968.703.594-.187 1.219-.281 1.985-.281.765 0 1.39.094 1.953.265.484-.437 1.344-.765 1.969-.687.218.422.25 1.515.046 2.047.5.593.766 1.39.766 2.203 0 1.922-1.453 3.375-3.547 3.64.531.344.89 1.094.89 1.954v1.625c0 .468.391.734.86.547C13.781 14.359 16 11.53 16 8.03 16 3.61 12.406 0 7.984 0 3.563 0 0 3.61 0 8.031a7.88 7.88 0 0 0 5.172 7.422c.422.156.828-.125.828-.547v-1.25c-.219.094-.5.156-.75.156-1.031 0-1.64-.562-2.078-1.609-.172-.422-.36-.672-.719-.719-.187-.015-.25-.093-.25-.187 0-.188.313-.328.625-.328.453 0 .844.281 1.25.86.313.452.64.655 1.031.655s.641-.14 1-.5c.266-.265.47-.5.657-.656"
}
