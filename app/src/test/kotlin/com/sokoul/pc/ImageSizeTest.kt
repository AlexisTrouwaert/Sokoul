package com.sokoul.pc

import com.sokoul.desktop.ui.ImageLoader
import org.junit.Assert.assertEquals
import org.junit.Test

/** The widths an image is decoded at: the next step up from where it is shown, full size past 1024 px. */
class ImageSizeTest {
    @Test fun `a card asks for the next step up`() {
        assertEquals(0, ImageLoader.bucket(0))
        assertEquals(256, ImageLoader.bucket(180))
        assertEquals(512, ImageLoader.bucket(300))
        assertEquals(512, ImageLoader.bucket(512))
        assertEquals(1024, ImageLoader.bucket(700))
        // A full-screen background keeps its full size.
        assertEquals(0, ImageLoader.bucket(1920))
    }
}
