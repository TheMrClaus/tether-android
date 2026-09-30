package com.tether.app.ui.files

import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Robolectric's Magnifier has no surface, so the one a text selection shows throws when it is
 * dismissed (as in feature/chat's selection tests). The preview's copy test runs with this no-op.
 */
@Implements(android.widget.Magnifier::class)
class NoMagnifier {
    @Implementation fun show(sourceCenterX: Float, sourceCenterY: Float) = Unit

    @Implementation fun show(sourceCenterX: Float, sourceCenterY: Float, magnifierCenterX: Float, magnifierCenterY: Float) = Unit

    @Implementation fun dismiss() = Unit

    @Implementation fun update() = Unit
}
