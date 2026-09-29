package com.tether.app.ui.chat

import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * T6.7: Robolectric's Magnifier has no surface, so the one a text selection shows throws when it
 * is dismissed. The selection tests run with this no-op in its place; nothing else changes.
 */
@Implements(android.widget.Magnifier::class)
class NoMagnifier {
    @Implementation fun show(sourceCenterX: Float, sourceCenterY: Float) = Unit

    @Implementation fun show(sourceCenterX: Float, sourceCenterY: Float, magnifierCenterX: Float, magnifierCenterY: Float) = Unit

    @Implementation fun dismiss() = Unit

    @Implementation fun update() = Unit
}
