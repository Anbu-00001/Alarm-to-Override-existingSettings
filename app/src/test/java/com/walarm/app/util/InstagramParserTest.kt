package com.walarm.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramParserTest {

    @Test
    fun `Instagram package registration is correct`() {
        assertTrue(InstagramParser.supportedPackages.contains("com.instagram.android"))
        assertTrue(InstagramParser.supportedPackages.contains("com.instagram.lite"))
        assertEquals(2, InstagramParser.supportedPackages.size)
    }
}
