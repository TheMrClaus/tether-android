package com.tether.app.ui

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel

/**
 * T7.4 r2 (test hygiene): the view models a test built, ended in its `@After` BEFORE the client
 * stops and BEFORE `Dispatchers.resetMain()`.
 *
 * A test never clears its view model (nothing calls onCleared), so its collectors stay subscribed
 * to the client's flows on Main after the test. Over a real client that is a live hazard: the
 * harness's `stop()` writes those flows (and its store clear writes them again, asynchronously,
 * through the init block's `combine(settings.baseUrl, settings.credential)`), and a write that
 * lands after `resetMain()` resumes a Main collector with no Main dispatcher. The exception
 * escapes the client scope and fails whichever `runTest` runs next in the fork
 * (`UncaughtExceptionsBeforeTest`). Cancelling the view model's scope first unsubscribes them.
 */
class TestViewModels {
    private val owned = mutableListOf<TetherViewModel>()

    fun <T : TetherViewModel> track(vm: T): T = vm.also { owned += it }

    /** Cancels every tracked view model's scope (call it first in `@After`). */
    fun clear() {
        owned.forEach { it.viewModelScope.cancel() }
        owned.clear()
    }
}
