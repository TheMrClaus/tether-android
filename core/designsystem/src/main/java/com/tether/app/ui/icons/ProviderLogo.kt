package com.tether.app.ui.icons

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tether.app.ui.theme.JetBrainsMono
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.TetherWeights
import java.util.Locale

/**
 * One entry of the web's `LOGO_MARKS` (components/provider-logo.tsx): an SVG `viewBox`
 * ("minX minY width height"), a single [path] filled with currentColor (`fill-rule="evenodd"` when
 * [evenOdd]), or — when [colored] is set — several parts that keep their own brand fills and
 * ignore currentColor (the web renders only the parts then, never [path]).
 */
@Immutable
data class ProviderMarkSpec(
    val viewBox: String,
    val path: String = "",
    val evenOdd: Boolean = false,
    val colored: List<ProviderMarkPart>? = null,
)

/** A `colored` part of a [ProviderMarkSpec]: path data [d] and its fixed CSS hex [fill]. */
@Immutable
data class ProviderMarkPart(val fill: String, val d: String)

/**
 * Port of the web's components/provider-logo.tsx (issue #59): the brand marks for the agents
 * Tether drives, dropped in for the single-letter provider glyph.
 *
 * The table is the web's LOGO_MARKS at 90fbb9f, path data byte-identical: claude, codex and
 * opencode (simple-icons, CC0, 24x24), pi (4x4 pixel mark, evenodd), reasonix (the DeepSeek
 * whale, viewBox 140 143 860 860), gemini (Google's four-colour "G", fills baked in), and dsh
 * (the other DeepSeek harness: the same whale). Every other provider id (acp, unknown, future)
 * renders the letter fallback.
 */
object ProviderLogos {
    private val CLAUDE = ProviderMarkSpec(
        viewBox = "0 0 24 24",
        path = "m4.7144 15.9555 4.7174-2.6471.079-.2307-.079-.1275h-.2307l-.7893-.0486-2.6956-.0729-2.3375-.0971-2.2646-.1214-.5707-.1215-.5343-.7042.0546-.3522.4797-.3218.686.0608 1.5179.1032 2.2767.1578 1.6514.0972 2.4468.255h.3886l.0546-.1579-.1336-.0971-.1032-.0972L6.973 9.8356l-2.55-1.6879-1.3356-.9714-.7225-.4918-.3643-.4614-.1578-1.0078.6557-.7225.8803.0607.2246.0607.8925.686 1.9064 1.4754 2.4893 1.8336.3643.3035.1457-.1032.0182-.0728-.164-.2733-1.3539-2.4467-1.445-2.4893-.6435-1.032-.17-.6194c-.0607-.255-.1032-.4674-.1032-.7285L6.287.1335 6.6997 0l.9957.1336.419.3642.6192 1.4147 1.0018 2.2282 1.5543 3.0296.4553.8985.2429.8318.091.255h.1579v-.1457l.1275-1.706.2368-2.0947.2307-2.6957.0789-.7589.3764-.9107.7468-.4918.5828.2793.4797.686-.0668.4433-.2853 1.8517-.5586 2.9021-.3643 1.9429h.2125l.2429-.2429.9835-1.3053 1.6514-2.0643.7286-.8196.85-.9046.5464-.4311h1.0321l.759 1.1293-.34 1.1657-1.0625 1.3478-.8804 1.1414-1.2628 1.7-.7893 1.36.0729.1093.1882-.0183 2.8535-.607 1.5421-.2794 1.8396-.3157.8318.3886.091.3946-.3278.8075-1.967.4857-2.3072.4614-3.4364.8136-.0425.0304.0486.0607 1.5482.1457.6618.0364h1.621l3.0175.2247.7892.522.4736.6376-.079.4857-1.2142.6193-1.6393-.3886-3.825-.9107-1.3113-.3279h-.1822v.1093l1.0929 1.0686 2.0035 1.8092 2.5075 2.3314.1275.5768-.3218.4554-.34-.0486-2.2039-1.6575-.85-.7468-1.9246-1.621h-.1275v.17l.4432.6496 2.3436 3.5214.1214 1.0807-.17.3521-.6071.2125-.6679-.1214-1.3721-1.9246L14.38 17.959l-1.1414-1.9428-.1397.079-.674 7.2552-.3156.3703-.7286.2793-.6071-.4614-.3218-.7468.3218-1.4753.3886-1.9246.3157-1.53.2853-1.9004.17-.6314-.0121-.0425-.1397.0182-1.4328 1.9672-2.1796 2.9446-1.7243 1.8456-.4128.164-.7164-.3704.0667-.6618.4008-.5889 2.386-3.0357 1.4389-1.882.929-1.0868-.0062-.1579h-.0546l-6.3385 4.1164-1.1293.1457-.4857-.4554.0608-.7467.2307-.2429 1.9064-1.3114Z",
    )

    private val CODEX = ProviderMarkSpec(
        viewBox = "0 0 24 24",
        path = "M22.2819 9.8211a5.9847 5.9847 0 0 0-.5157-4.9108 6.0462 6.0462 0 0 0-6.5098-2.9A6.0651 6.0651 0 0 0 4.9807 4.1818a5.9847 5.9847 0 0 0-3.9977 2.9 6.0462 6.0462 0 0 0 .7427 7.0966 5.98 5.98 0 0 0 .511 4.9107 6.051 6.051 0 0 0 6.5146 2.9001A5.9847 5.9847 0 0 0 13.2599 24a6.0557 6.0557 0 0 0 5.7718-4.2058 5.9894 5.9894 0 0 0 3.9977-2.9001 6.0557 6.0557 0 0 0-.7475-7.0729zm-9.022 12.6081a4.4755 4.4755 0 0 1-2.8764-1.0408l.1419-.0804 4.7783-2.7582a.7948.7948 0 0 0 .3927-.6813v-6.7369l2.02 1.1686a.071.071 0 0 1 .038.052v5.5826a4.504 4.504 0 0 1-4.4945 4.4944zm-9.6607-4.1254a4.4708 4.4708 0 0 1-.5346-3.0137l.142.0852 4.783 2.7582a.7712.7712 0 0 0 .7806 0l5.8428-3.3685v2.3324a.0804.0804 0 0 1-.0332.0615L9.74 19.9502a4.4992 4.4992 0 0 1-6.1408-1.6464zM2.3408 7.8956a4.485 4.485 0 0 1 2.3655-1.9728V11.6a.7664.7664 0 0 0 .3879.6765l5.8144 3.3543-2.0201 1.1685a.0757.0757 0 0 1-.071 0l-4.8303-2.7865A4.504 4.504 0 0 1 2.3408 7.872zm16.5963 3.8558L13.1038 8.364 15.1192 7.2a.0757.0757 0 0 1 .071 0l4.8303 2.7913a4.4944 4.4944 0 0 1-.6765 8.1042v-5.6772a.79.79 0 0 0-.407-.667zm2.0107-3.0231l-.142-.0852-4.7735-2.7818a.7759.7759 0 0 0-.7854 0L9.409 9.2297V6.8974a.0662.0662 0 0 1 .0284-.0615l4.8303-2.7866a4.4992 4.4992 0 0 1 6.6802 4.66zM8.3065 12.863l-2.02-1.1638a.0804.0804 0 0 1-.038-.0567V6.0742a4.4992 4.4992 0 0 1 7.3757-3.4537l-.142.0805L8.704 5.459a.7948.7948 0 0 0-.3927.6813zm1.0976-2.3654l2.602-1.4998 2.6069 1.4998v2.9994l-2.5974 1.4997-2.6067-1.4997Z",
    )

    private val PI = ProviderMarkSpec(
        viewBox = "0 0 4 4",
        path = "M0 0H3V2H2V3H1V4H0ZM1 1V2H2V1ZM3 2H4V4H3Z",
        evenOdd = true,
    )

    private val REASONIX = ProviderMarkSpec(
        viewBox = "140 143 860 860",
        path = "M968.002 326.025C959.5 321.85 955.842 329.804 950.865 333.835C949.162 335.143 947.722 336.834 946.283 338.393C933.859 351.673 919.336 360.407 900.376 359.363C872.649 357.804 848.963 366.525 828.035 387.759C823.586 361.583 808.812 345.951 786.313 335.936C774.536 330.729 762.64 325.51 754.401 314.186C748.645 306.112 747.074 297.127 744.195 288.274C742.36 282.936 740.538 277.465 734.385 276.554C727.718 275.51 725.103 281.112 722.476 285.803C712.006 304.95 707.952 326.039 708.349 347.405C709.26 395.463 729.54 433.742 769.823 460.962C774.404 464.094 775.579 467.212 774.14 471.771C771.394 481.153 768.119 490.257 765.241 499.639C763.406 505.624 760.66 506.933 754.256 504.329C732.154 495.08 713.049 481.417 696.175 464.874C667.524 437.138 641.633 406.536 609.325 382.566C601.733 376.964 594.154 371.758 586.298 366.802C553.33 334.773 590.616 308.464 599.251 305.333C608.282 302.082 602.393 290.877 573.214 291.009C544.048 291.141 517.364 300.906 483.353 313.935C478.389 315.891 473.147 317.318 467.786 318.494C436.917 312.64 404.873 311.332 371.377 315.111C308.332 322.141 257.975 351.964 220.953 402.876C176.471 464.081 166.014 533.624 178.835 606.153C192.302 682.594 231.291 745.887 291.194 795.372C353.328 846.68 424.876 871.813 506.498 866.99C556.076 864.122 611.279 857.489 673.532 804.74C689.23 812.55 705.708 815.681 733.052 818.02C754.111 819.976 774.391 816.976 790.076 813.726C814.674 808.519 812.971 785.726 804.072 781.564C731.995 747.962 747.826 761.638 733.435 750.565C770.06 707.198 825.263 662.139 846.85 516.156C848.553 504.567 847.114 497.273 846.85 487.892C846.718 482.157 848.025 479.95 854.574 479.303C872.623 477.215 890.156 472.273 906.238 463.42C952.938 437.892 971.765 395.965 976.215 345.7C976.875 338.01 976.083 330.069 967.976 326.039L968.002 326.025ZM561.067 778.432C491.222 723.477 457.343 705.374 443.347 706.154C430.263 706.933 432.626 721.918 435.505 731.682C438.515 741.315 442.436 747.962 447.929 756.418C451.718 762.021 454.346 770.345 444.14 776.609C421.641 790.549 382.533 771.918 380.698 771.006C335.174 744.182 297.109 708.757 270.293 660.316C244.389 613.698 229.35 563.685 226.868 510.302C226.208 497.406 230.01 492.847 242.831 490.508C259.704 487.376 277.106 486.729 293.98 489.2C365.264 499.625 425.959 531.523 476.844 582.052C505.878 610.831 527.861 645.213 550.491 678.815C574.561 714.492 600.452 748.49 633.42 776.358C645.066 786.122 654.348 793.548 663.247 799.019C636.431 802.018 591.698 802.666 561.08 778.445L561.067 778.432ZM594.55 562.905C594.55 557.171 599.132 552.625 604.888 552.625C606.195 552.625 607.371 552.876 608.414 553.273C609.853 553.801 611.16 554.581 612.203 555.744C614.038 557.567 615.081 560.17 615.081 562.905C615.081 568.64 610.5 573.185 604.743 573.185C598.987 573.185 594.537 568.627 594.537 562.905H594.55ZM698.539 616.301C691.871 619.037 685.19 621.375 678.787 621.64C668.845 622.155 657.992 618.125 652.103 613.17C642.953 605.479 636.404 601.185 633.658 587.773C632.483 582.039 633.13 573.185 634.186 568.111C636.536 557.171 633.922 550.141 626.212 543.759C619.927 538.553 611.952 537.112 603.185 537.112C599.911 537.112 596.914 535.685 594.682 534.509C591.025 532.686 588.015 528.127 590.893 522.525C591.804 520.701 596.254 516.275 597.297 515.495C609.206 508.716 622.937 510.936 635.625 516.01C647.403 520.833 656.288 529.686 669.109 542.186C682.193 557.289 684.543 561.465 692.003 572.789C697.892 581.642 703.252 590.759 706.909 601.185C709.128 607.699 706.249 613.038 698.539 616.288V616.301Z",
    )

    private val GEMINI = ProviderMarkSpec(
        viewBox = "4 4 40 40",
        colored = listOf(
            ProviderMarkPart(fill = "#FFC107", d = "M43.611,20.083H42V20H24v8h11.303c-1.649,4.657-6.08,8-11.303,8c-6.627,0-12-5.373-12-12c0-6.627,5.373-12,12-12c3.059,0,5.842,1.154,7.961,3.039l5.657-5.657C34.046,6.053,29.268,4,24,4C12.955,4,4,12.955,4,24c0,11.045,8.955,20,20,20c11.045,0,20-8.955,20-20C44,22.659,43.862,21.35,43.611,20.083z"),
            ProviderMarkPart(fill = "#FF3D00", d = "M6.306,14.691l6.571,4.819C14.655,15.108,18.961,12,24,12c3.059,0,5.842,1.154,7.961,3.039l5.657-5.657C34.046,6.053,29.268,4,24,4C16.318,4,9.656,8.337,6.306,14.691z"),
            ProviderMarkPart(fill = "#4CAF50", d = "M24,44c5.166,0,9.86-1.977,13.409-5.192l-6.19-5.238C29.211,35.091,26.715,36,24,36c-5.202,0-9.619-3.317-11.283-7.946l-6.522,5.025C9.505,39.556,16.227,44,24,44z"),
            ProviderMarkPart(fill = "#1976D2", d = "M43.611,20.083H42V20H24v8h11.303c-0.792,2.237-2.231,4.166-4.087,5.571l6.19,5.238C36.971,39.205,44,34,44,24C44,22.659,43.862,21.35,43.611,20.083z"),
        ),
    )

    private val OPENCODE = ProviderMarkSpec(
        viewBox = "0 0 24 24",
        path = "M22 24H2V0h20zM17 4.8H7v14.4h10z",
    )

    /** The web's LOGO_MARKS, keyed by provider id, in the web's key order (`dsh` is `reasonix`). */
    val marks: Map<String, ProviderMarkSpec> = linkedMapOf(
        "claude" to CLAUDE,
        "codex" to CODEX,
        "pi" to PI,
        "reasonix" to REASONIX,
        "gemini" to GEMINI,
        "dsh" to REASONIX,
        "opencode" to OPENCODE,
    )

    private val vectors: Map<String, ImageVector> by lazy {
        marks.mapValues { (id, spec) -> markVector(id, spec) }
    }

    /** The brand mark for [provider], or null when the web renders the letter fallback. */
    fun mark(provider: String?): ImageVector? = if (provider.isNullOrEmpty()) null else vectors[provider]

    /**
     * The web's `data-brand` on the rendered svg: the provider id when it has a mark, else null
     * (the letter fallback carries no brand). Containers theme their tile from this.
     */
    fun brand(provider: String?): String? = provider?.takeIf { mark(it) != null }

    /** True when [provider]'s mark draws its own `colored` fills, so no tint may be applied. */
    fun keepsOwnColours(provider: String?): Boolean =
        !provider.isNullOrEmpty() && marks[provider]?.colored != null

    /**
     * The letter the web shows when there is no mark:
     * `fallback ?? (provider ? provider.slice(0, 1).toUpperCase() : "?")` — an empty provider id is
     * falsy in JS, so it also yields "?".
     */
    fun fallbackLetter(provider: String?, fallback: String? = null): String =
        fallback ?: if (provider.isNullOrEmpty()) "?" else provider.substring(0, 1).uppercase(Locale.ROOT)

    /** An SVG `viewBox` as (minX, minY, width, height); whitespace and/or comma separated. */
    internal fun parseViewBox(viewBox: String): List<Float> {
        val v = viewBox.trim().split(Regex("""[\s,]+""")).map { it.toFloat() }
        require(v.size == 4 && v[2] > 0f && v[3] > 0f) { "bad viewBox: $viewBox" }
        return v
    }

    /** A CSS `#rgb` / `#rrggbb` fill. */
    internal fun parseFill(fill: String): Color {
        val hex = fill.removePrefix("#").let { h -> if (h.length == 3) h.map { "$it$it" }.joinToString("") else h }
        require(hex.length == 6) { "bad fill: $fill" }
        return Color(0xFF000000 or hex.toLong(16))
    }

    private fun markVector(id: String, spec: ProviderMarkSpec): ImageVector {
        val (minX, minY, w, h) = parseViewBox(spec.viewBox)
        // The svg box is square on the web (`width = height`); the mark scales to fit, as
        // preserveAspectRatio's default xMidYMid meet, which Icon's ContentScale.Fit reproduces.
        val scale = 24f / maxOf(w, h)
        return ImageVector.Builder(
            name = "ProviderLogo.$id",
            defaultWidth = (w * scale).dp,
            defaultHeight = (h * scale).dp,
            viewportWidth = w,
            viewportHeight = h,
        ).group(translationX = -minX, translationY = -minY) {
            val colored = spec.colored
            if (colored != null) {
                for (part in colored) {
                    addPath(pathData = PathParser().parsePathString(part.d).toNodes(), fill = SolidColor(parseFill(part.fill)))
                }
            } else {
                addPath(
                    pathData = PathParser().parsePathString(spec.path).toNodes(),
                    pathFillType = if (spec.evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
                    fill = SolidColor(Color.Black), // tinted to currentColor by Icon
                )
            }
        }.build()
    }
}

/** Sizing and colour of the web's `.provider-glyph` container that hosts a [ProviderLogo]. */
object ProviderLogoDefaults {
    /** `.provider-glyph svg.provider-logo-svg { width: 58%; height: 58% }`. */
    const val MARK_FRACTION: Float = 0.58f

    /** The default `.provider-glyph` circle is 2rem. */
    val GlyphSize: Dp = 32.dp

    /** The mark's size inside a glyph circle of [glyphSize]. */
    fun markSize(glyphSize: Dp = GlyphSize): Dp = glyphSize * MARK_FRACTION

    /** `.provider-glyph { font-size: 0.8rem }` — the letter fallback. */
    val LetterSize: TextUnit = 12.8.sp

    /**
     * currentColor inside `.provider-glyph`: `var(--white)`, overridden to `var(--ink)` by
     * `.provider-claude, .provider-codex, .provider-opencode` — per theme, from the tokens.
     */
    @Composable
    fun color(provider: String?): Color {
        val t = LocalTetherTokens.current
        return if (provider in INK_PROVIDERS) t.ink else t.white
    }

    internal val INK_PROVIDERS = setOf("claude", "codex", "opencode")
}

/**
 * Drop-in for a bare provider letter: the brand mark when one is verified for [provider], otherwise
 * [fallback] (or the provider's first letter, or "?"), exactly as provider-logo.tsx. The container
 * themes itself from [ProviderLogos.brand] (the web's `data-brand`).
 *
 * Sizing belongs to the caller's container (the web's `.provider-glyph` gives the mark 58%); the
 * defaults reproduce the stock 2rem glyph circle.
 */
@Composable
fun ProviderLogo(
    provider: String?,
    modifier: Modifier = Modifier,
    fallback: String? = null,
    color: Color = LocalContentColor.current,
    markSize: Dp = ProviderLogoDefaults.markSize(),
    letterSize: TextUnit = ProviderLogoDefaults.LetterSize,
) {
    val mark = ProviderLogos.mark(provider)
    if (mark != null) {
        // aria-hidden on the web: decorative, the row carries the provider name. A `colored` mark
        // (gemini) keeps its own fills: [color] is currentColor, which those parts never read.
        val tint = if (ProviderLogos.keepsOwnColours(provider)) Color.Unspecified else color
        Icon(mark, contentDescription = null, modifier = modifier.size(markSize), tint = tint)
    } else {
        Text(
            ProviderLogos.fallbackLetter(provider, fallback),
            modifier = modifier,
            color = color,
            fontFamily = JetBrainsMono,
            fontWeight = TetherWeights.glyph,
            fontSize = letterSize,
            maxLines = 1,
        )
    }
}
