package org.read.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentLineFollowLogicTest {

    @Test
    fun shouldPauseAutoFollowForDrag_onlyWhenReaderIsVisibleAndSpeaking() {
        assertTrue(
            shouldPauseAutoFollowForDrag(
                showDocument = true,
                manualReaderDragActive = true,
                visualPlaybackIsSpeaking = true
            )
        )
        assertFalse(
            shouldPauseAutoFollowForDrag(
                showDocument = true,
                manualReaderDragActive = false,
                visualPlaybackIsSpeaking = true
            )
        )
        assertFalse(
            shouldPauseAutoFollowForDrag(
                showDocument = false,
                manualReaderDragActive = true,
                visualPlaybackIsSpeaking = true
            )
        )
    }
}
