package com.tether.app.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** A frame carrying a secret must not print it (T1.4 review); the wire form still does. */
class RedactionTest {
    @Test
    fun nodeAddNeverPrintsItsCredential() {
        val frame = ClientMessage.NodeAdd(credential = "tthr_n0de-s3cret", label = "box", baseUrl = "https://b", requestId = "r1")
        assertFalse(frame.toString().contains("s3cret"))
        assertEquals("NodeAdd(credential=***, label=box, baseUrl=https://b, requestId=r1)", frame.toString())
        assertEquals(true, frame.encode().contains("tthr_n0de-s3cret"))
    }
}
