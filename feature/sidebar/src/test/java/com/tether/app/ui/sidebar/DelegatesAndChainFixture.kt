package com.tether.app.ui.sidebar

/**
 * ta-hkk7.7: a chain head WITH a delegate and earlier sessions (NestedFixture has no head with both): head c2 over c1, c0, and one live delegate
 * cd of c2. The chain toggle follows the delegate block, so it draws the web's constant 14 dp stub (studio.css:1388) up into the preceding sibling.
 */
internal object DelegatesAndChainFixture {
    private val F = SidebarFixtures
    val sessions = F.chain(3, "c", ago = 5, names = listOf("Probe the parser", "Plan the importer", "Ship the importer")) +
        F.live("cd", "Check the links", ago = 6, parent = "c2")
    val state get() = F.state(sessions, current = F.APP, activeId = "c2")
    val open = SidebarUiSeed(openChains = setOf("live:c0"), openChildren = setOf("live:c2"))
    val delegatesClosed = SidebarUiSeed(openChains = setOf("live:c0"))
}
