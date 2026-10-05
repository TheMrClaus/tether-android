package com.tether.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tether.app.client.TetherClient
import com.tether.app.protocol.model.DirectoryListing
import com.tether.app.ui.FolderPickerDialog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The words of the home "Browse folders" (settings-dialog.tsx 90fbb9f :746-747, :2184-2185, :2478-2479). */
object HomeFolderCopy {
    const val BROWSE_TITLE = "Browse folders"
    const val PICKER_TITLE = "Choose a home directory"
    fun browseLabel(name: String) = "Browse for $name home"
}

/** Test tags of the Browse folders keys. */
object HomeFolderTags {
    fun engine(id: String) = "engine-home-browse:$id"
    fun profile(id: String) = "profile-home-browse:$id"
}

/**
 * T8.2: Settings' one home picker (settings-dialog.tsx 90fbb9f :1944-1947 `homeBrowseTarget` /
 * `profileHomeBrowseId` / `homePickerRef`, :2457-2480): an engine card's or a custom provider's
 * "Browse folders" lists a folder and opens the shared [FolderPickerDialog]; the folder chosen fills
 * that home (written as the web's `onSelect` writes it).
 */
class HomeFolderPicker(
    val directories: StateFlow<DirectoryListing?>,
    private val browse: (String?) -> Unit,
    val createFolder: (cwd: String, name: String) -> Unit,
) {
    /** What is being browsed for: the folder shown before the first listing, and what a pick writes. */
    class Target(val root: String, val onPick: (String) -> Unit)

    var target by mutableStateOf<Target?>(null)
        private set

    /** :2182 / :2240-2245: browse from [from] (else the server's default folder) and open the picker. */
    fun open(root: String, from: String?, onPick: (String) -> Unit) {
        target = Target(root, onPick)
        browse(from?.takeIf { it.isNotEmpty() })
    }

    fun close() {
        target = null
    }

    fun browseTo(cwd: String) = browse(cwd)

    companion object {
        /** No client (the goldens): the keys draw, nothing is listed. */
        val None = HomeFolderPicker(MutableStateFlow(null), {}, { _, _ -> })

        fun of(client: TetherClient) = HomeFolderPicker(client.directories, { client.browse(it) }, { cwd, name -> client.createFolder(cwd, name) })
    }
}

val LocalHomeFolderPicker = staticCompositionLocalOf { HomeFolderPicker.None }

/** :2457-2480: the picker, while a home is being browsed for ("Engine" / "Choose a home directory"). */
@Composable
internal fun HomeFolderPickerHost(picker: HomeFolderPicker) {
    val target = picker.target ?: return
    val directories by picker.directories.collectAsStateWithLifecycle()
    FolderPickerDialog(
        directories = directories,
        current = target.root,
        onDismiss = picker::close,
        onBrowse = picker::browseTo,
        onChoose = { path ->
            target.onPick(path)
            picker.close()
        },
        title = HomeFolderCopy.PICKER_TITLE,
        onCreateFolder = picker.createFolder,
    )
}
