package com.tether.app.ui.state

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.currentCompositeKeyHashCode
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner

/**
 * ta-coik.20: a field value that survives a configuration change (a rotation) and is NEVER written
 * to the saved-instance-state Bundle, for secrets: passwords, tokens, pairing codes, credentials,
 * secret env values. A browser keeps a typed password across a resize and drops it on a reload;
 * this is the same: the value rides the activity's [ViewModel] store (memory only, kept through a
 * recreation, cleared when the activity finishes) so nothing secret reaches disk, and a process
 * death starts the field empty, like a reload.
 *
 * The slot is keyed by the call site's composite key (what `rememberSaveable` keys on) and the
 * [inputs]; it is dropped once the composable leaves for any reason other than a configuration
 * change (the dialog closed, the screen left, the inputs changed), so reopening starts empty, as a
 * plain `remember` did. Without a store owner (a preview) it is a plain `remember`.
 */
@Composable
fun <T> rememberRetained(vararg inputs: Any?, init: () -> T): MutableState<T> {
    val owner = LocalViewModelStoreOwner.current ?: return remember(*inputs) { mutableStateOf(init()) }
    val store = remember(owner) { ViewModelProvider(owner, STORE_FACTORY)[RetainedStore::class.java] }
    val activity = LocalContext.current.findActivity()
    val key = currentCompositeKeyHashCode.toString(36)
    val holder = remember(store, key, *inputs) { RetainedHolder(store, key, inputs.toList(), activity, init) }
    return holder.state
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

private class Slot(val inputs: List<Any?>, val state: MutableState<Any?>)

/** The per-activity memory: lives through a recreation, cleared when the activity is really finished. */
internal class RetainedStore : ViewModel() {
    private val slots = HashMap<String, Slot>()

    @Suppress("UNCHECKED_CAST")
    fun <T> claim(key: String, inputs: List<Any?>, init: () -> T): Pair<MutableState<T>, Boolean> {
        val kept = slots[key]
        if (kept != null && kept.inputs == inputs) return (kept.state as MutableState<T>) to false
        val fresh = Slot(inputs, mutableStateOf<Any?>(init()))
        slots[key] = fresh
        return (fresh.state as MutableState<T>) to true
    }

    fun release(key: String, state: MutableState<*>) {
        // Only the slot's own holder may drop it: a re-keyed holder composes before the old one is forgotten.
        if (slots[key]?.state === state) slots.remove(key)
    }

    /** Test seam: how many secrets are being held. */
    fun size(): Int = slots.size

    override fun onCleared() {
        slots.clear()
    }
}

private val STORE_FACTORY = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = RetainedStore() as T
}

private class RetainedHolder<T>(
    private val store: RetainedStore,
    private val key: String,
    inputs: List<Any?>,
    private val activity: Activity?,
    init: () -> T,
) : RememberObserver {
    private val claimed = store.claim(key, inputs, init)
    val state: MutableState<T> = claimed.first
    private val fresh = claimed.second

    override fun onRemembered() = Unit

    override fun onForgotten() {
        if (activity?.isChangingConfigurations != true) store.release(key, state)
    }

    override fun onAbandoned() {
        if (fresh) store.release(key, state)
    }
}

/**
 * ta-coik.20: one form value that keeps its state across a configuration change. A non-[secret]
 * value is `rememberSaveable` (it must be Bundle-saveable: a String, a number, a Boolean, or null);
 * a [secret] value is [rememberRetained] (memory only, never the saved-instance Bundle). Both reset
 * when [inputs] change, as `remember(inputs)` did.
 */
@Composable
fun <T> rememberFormState(secret: Boolean, vararg inputs: Any?, init: () -> T): MutableState<T> =
    if (secret) {
        rememberRetained(*inputs, init = init)
    } else {
        androidx.compose.runtime.saveable.rememberSaveable(*inputs) { mutableStateOf(init()) }
    }
