package com.tether.app.ui.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.TetherDialogSurface
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalTetherTokens

/** The rows of the Upload chooser (ta-coik.67), the words a test and a screen reader find them by. */
object UploadChooserCopy {
    const val TITLE = "Upload files"
    const val CHOOSE_FILES = "Choose files"
    const val TAKE_PHOTO = "Take photo"
}

/**
 * What Android Chrome shows for the web's bare `<input type="file" multiple>`: the files, and the
 * camera beside them. The Upload key opens this; "Choose files" is the system document picker (the
 * key's behaviour before), "Take photo" the system camera.
 */
@Composable
fun UploadChooserContent(
    onChooseFiles: () -> Unit,
    onTakePhoto: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalTetherTokens.current
    TetherDialogSurface(
        modifier = modifier,
        title = UploadChooserCopy.TITLE,
        footer = { TetherKey(onClick = onCancel, classes = KeyClasses.ButtonSecondary, label = "Cancel") },
    ) {
        Column(Modifier.padding(top = t.css.spaceMd), verticalArrangement = Arrangement.spacedBy(t.css.spaceXs)) {
            ChooserRow(TetherIcons.File, UploadChooserCopy.CHOOSE_FILES, onChooseFiles)
            ChooserRow(TetherIcons.Camera, UploadChooserCopy.TAKE_PHOTO, onTakePhoto)
        }
    }
}

@Composable
private fun ChooserRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    val t = LocalTetherTokens.current
    TetherKey(
        onClick = onClick,
        classes = KeyClasses.ButtonSecondary,
        label = label,
        icon = icon,
        iconSize = 16.dp,
        fixedVerb = false,
        contentArrangement = Arrangement.spacedBy(t.css.spaceSm, Alignment.Start),
        modifier = Modifier.fillMaxWidth(),
    )
}
