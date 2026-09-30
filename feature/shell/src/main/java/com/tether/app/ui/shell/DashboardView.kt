package com.tether.app.ui.shell

/**
 * T15.4: the console's top-level views (lib/dashboard-view.mjs `DASHBOARD_VIEWS`,
 * OVERVIEW_STUDIO_PLAN.md §4). [key] is the web's `?view=` value and what is remembered.
 */
enum class DashboardView(val key: String) {
    Overview("overview"),
    Sessions("sessions"),
    Scheduled("scheduled"),
    ;

    companion object {
        /** `normalizeView`: a known view id, else null. */
        fun of(key: String?): DashboardView? = entries.firstOrNull { it.key == key }
    }
}

/**
 * The top bar's destinations (components/topbar.tsx `DESTINATIONS`, in order): the three views
 * and Usage, which on the web is a page of its own (`/usage`), never a remembered view.
 */
enum class TopBarDestination(val label: String, val view: DashboardView?) {
    Overview("Overview", DashboardView.Overview),
    Sessions("Sessions", DashboardView.Sessions),
    Scheduled("Scheduled", DashboardView.Scheduled),
    Usage("Usage", null),
    ;

    companion object {
        fun of(view: DashboardView): TopBarDestination = entries.first { it.view == view }
    }
}

/** Why a view was chosen at boot (`resolveDashboardView`'s `source`). */
enum class ViewSource { SessionLink, Stored, ExistingInstall, FreshInstall }

/**
 * lib/dashboard-view.mjs `resolveDashboardView` for the app: a session opened by a link (a
 * notification, a deep link, a session link) always wins and shows Sessions; else the remembered
 * last view; else an install that already kept preferences before that record existed keeps
 * last-session restoration (Sessions); a fresh install starts on the Overview. The app has no URL,
 * so the web's `?view=` step has no counterpart; a link carries a session or nothing.
 */
object DashboardViews {
    fun resolve(sessionLink: Boolean, storedView: String?, hasExistingPreferences: Boolean): Pair<DashboardView, ViewSource> = when {
        sessionLink -> DashboardView.Sessions to ViewSource.SessionLink
        DashboardView.of(storedView) != null -> DashboardView.of(storedView)!! to ViewSource.Stored
        hasExistingPreferences -> DashboardView.Sessions to ViewSource.ExistingInstall
        else -> DashboardView.Overview to ViewSource.FreshInstall
    }
}

/**
 * The views behind the one on screen, oldest first: Android Back plays the web's browser Back
 * between major views (dashboard.tsx `pushView`). A navigation to a different view pushes the
 * current one; one to the view already showing is a no-op (`if (readView() === next) return`), so
 * session-to-session selection never grows it (the web's replace-history behaviour). Back pops;
 * empty, Back leaves the app. Bounded so a long day of switching cannot grow the saved state.
 */
data class ViewHistory(val current: DashboardView?, val behind: List<DashboardView> = emptyList()) {
    /** Show [next]; the view it replaces goes behind it. Null [current] (boot) pushes nothing. */
    fun navigate(next: DashboardView): ViewHistory = when (current) {
        next -> this
        null -> copy(current = next)
        else -> ViewHistory(next, (behind + current).takeLast(MAX_BEHIND))
    }

    val canGoBack: Boolean get() = behind.isNotEmpty()

    fun back(): ViewHistory = if (behind.isEmpty()) this else ViewHistory(behind.last(), behind.dropLast(1))

    /** One string for the saved-instance state (`rememberSaveable`). */
    fun encode(): String = (listOf(current?.key.orEmpty()) + behind.map { it.key }).joinToString(",")

    companion object {
        const val MAX_BEHIND = 32

        fun decode(saved: String): ViewHistory {
            val parts = saved.split(',')
            return ViewHistory(DashboardView.of(parts.first()), parts.drop(1).mapNotNull(DashboardView::of))
        }
    }
}
