package com.tether.app.share

import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.appendDraftText
import com.tether.app.ui.chat.AttachmentStager
import com.tether.app.ui.chat.DraftAttachmentStager

/** T11.2: where the user sent a share. */
sealed interface ShareTarget {
    /** The new-session draft composer (the web's New session). */
    data object NewSession : ShareTarget

    /** An existing session's composer (a sidebar pick of it). */
    data class Session(val id: String) : ShareTarget
}

/**
 * T11.2: places a share in the composer the user chose, exactly as that composer's own attach and
 * paste would: the session is picked (or the new-session sheet raised), the text is added to the
 * draft and the files go through the composer's own stager, with its caps and flashes (the last
 * flash is shown, as the composer shows it). Nothing is sent.
 */
class ShareDelivery(private val vm: TetherViewModel) {
    private val sessionStager = AttachmentStager(vm.stagedAttachments, { vm.attachmentOrigin() }, allowed = vm::attachmentsAllowed)
    private val draftStager = DraftAttachmentStager(vm.draftComposer)

    suspend fun deliver(share: PendingShare, target: ShareTarget) {
        try {
            val flashes = when (target) {
                ShareTarget.NewSession -> {
                    share.text?.let { vm.draftComposer.setText(appendDraftText(vm.draftComposer.state.value.text, it)) }
                    vm.openDraft()
                    draftStager.stage(share.files)
                }
                is ShareTarget.Session -> {
                    vm.selectSession(target.id)
                    share.text?.let { vm.insertIntoComposer(target.id, it) }
                    sessionStager.stage(target.id, share.files)
                }
            }
            flashes.lastOrNull()?.let(vm::reportLocalError)
        } finally {
            share.deleteFiles()
        }
    }
}
