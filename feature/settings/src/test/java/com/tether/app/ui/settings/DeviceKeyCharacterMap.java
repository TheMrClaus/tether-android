package com.tether.app.ui.settings;

import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowKeyCharacterMap;

/**
 * ta-78a: a key held with Ctrl or Meta types no character, as on a device. The platform's key
 * character map (KeyCharacterMap.cpp, matchesMetaState) only gives a character for Ctrl / Meta when
 * the key's behaviour names that modifier, and the generic map names it for no letter. Robolectric's
 * map ignores the modifier, so Ctrl+C would TYPE "c" into a text field instead of copying. With this
 * shadow the text field sees Ctrl+C, Ctrl+X and Ctrl+Insert as the shortcuts they are on a device.
 */
@Implements(KeyCharacterMap.class)
public class DeviceKeyCharacterMap extends ShadowKeyCharacterMap {
    private static final int SHORTCUT_META = KeyEvent.META_CTRL_MASK | KeyEvent.META_META_MASK;

    @Implementation
    protected static char nativeGetCharacter(long ptr, int keyCode, int metaState) {
        if ((metaState & SHORTCUT_META) != 0) return 0;
        return ShadowKeyCharacterMap.nativeGetCharacter(ptr, keyCode, metaState);
    }
}
