package com.tether.app.ui.shell

import com.tether.app.ui.chat.FileLinks
import com.tether.app.ui.files.FileKinds
import org.junit.Assert.assertEquals
import org.junit.Test

/** ta-9jnm: chat cannot see the file browser's kinds, so the shell (which sees both) pins that every previewable file is a link candidate. */
class FileLinkKindsTest {
    @Test fun everyKindTheBrowserPreviewsIsAKindAMentionMayHave() {
        val previewable = FileKinds.IMAGE_EXTENSIONS + FileKinds.VIDEO_EXTENSIONS + FileKinds.TEXT_EXTENSIONS
        assertEquals("missing extensions: ${previewable - FileLinks.ALLOW}", emptySet<String>(), previewable - FileLinks.ALLOW)
        assertEquals("missing names: ${FileKinds.TEXT_FILENAMES - FileLinks.NAMES}", emptySet<String>(), FileKinds.TEXT_FILENAMES - FileLinks.NAMES)
    }
}
