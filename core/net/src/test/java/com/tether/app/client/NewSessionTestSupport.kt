package com.tether.app.client

import com.tether.app.protocol.helpers.DraftForm
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr

/** ta-8cv: a cold draft (no operator picks) in [cwd], as the draft composer submits one. */
fun coldDraftForm(cwd: String): JsObj = DraftForm.INITIAL_DRAFT_FORM.put("cwd", JsStr(cwd))

/**
 * ta-8cv: the ta-895 tests' create, now as a draft-composer request: a cold draft in [cwd], a fresh
 * requestId, composed on the client's current socket.
 */
fun TetherClient.createNewSession(choice: NewSessionChoice, cwd: String, expectedOrigin: String?, requestId: String = "req-${choice.key}"): NewSessionResult =
    createNewSession(NewSessionRequest(choice, coldDraftForm(cwd), DraftForm.INITIAL_USER_MODIFIED, requestId, linkEpoch.value), expectedOrigin)
