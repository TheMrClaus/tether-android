package com.tether.app.client

import org.junit.Assert.assertEquals
import org.junit.Test

/** ta-daw9: the public-picture client has no end-to-end call limit, like the browser's `<img>`. */
class PublicImagesTimeoutTest {
    @Test fun aSlowPublicPictureIsNotEndedByAnEndToEndCallTimeout() {
        val client = HttpPublicImages.defaultClient()
        assertEquals("no call timeout (0 = none)", 0, client.callTimeoutMillis)
        // A dead connection still ends it.
        assertEquals(15_000, client.connectTimeoutMillis)
        assertEquals(30_000, client.readTimeoutMillis)
    }
}
