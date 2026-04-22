package org.read.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class SummaryDisplayFormattingTest {

    @Test
    fun buildSummaryDisplayLinesForUi_keepsBulletContinuationLinesTogether() {
        val lines = buildSummaryDisplayLinesForUi(
            """
            Overview:
            A short overview.

            Key points:
            - First point starts here
            and continues on the next line.
            - Second point is separate.
            """.trimIndent()
        )

        val bulletLines = lines.filter { it.kind == SummaryDisplayLineKind.Bullet }

        assertEquals(2, bulletLines.size)
        assertEquals(
            "First point starts here and continues on the next line.",
            bulletLines[0].text
        )
        assertEquals("Second point is separate.", bulletLines[1].text)
    }

    @Test
    fun buildSummaryDisplayLinesForUi_splitsInlineBulletsIntoSeparateItems() {
        val lines = buildSummaryDisplayLinesForUi(
            """
            Overview:
            A short overview.

            Key points:
            - First point. - Second point. - Third point.
            """.trimIndent()
        )

        val bulletLines = lines.filter { it.kind == SummaryDisplayLineKind.Bullet }

        assertEquals(3, bulletLines.size)
        assertEquals("First point.", bulletLines[0].text)
        assertEquals("Second point.", bulletLines[1].text)
        assertEquals("Third point.", bulletLines[2].text)
    }

    @Test
    fun buildSummaryDisplayLinesForUi_startsNewBulletsForStandaloneLinesInBulletSection() {
        val lines = buildSummaryDisplayLinesForUi(
            """
            Overview:
            A short overview.

            Key points:
            - First point.
            Second point.
            Third point.
            """.trimIndent()
        )

        val bulletLines = lines.filter { it.kind == SummaryDisplayLineKind.Bullet }

        assertEquals(3, bulletLines.size)
        assertEquals("First point.", bulletLines[0].text)
        assertEquals("Second point.", bulletLines[1].text)
        assertEquals("Third point.", bulletLines[2].text)
    }

    @Test
    fun buildSummaryDisplayLinesForUi_handlesOnDeviceGluedBulletMarkers() {
        val lines = buildSummaryDisplayLinesForUi(
            """
            Overview:The document argues that AI reasoning models exhibit emergent, multi-agent-like behavior within themselves, termed "societies of thought."
            Key points:- Frontier reasoning models spontaneously generate internal debates and conversations, exhibiting multi-perspective interactions within their own chain of thought.- This conversational structure accounts for the models' accuracy advantage on hard reasoning tasks, suggesting that robust reasoning is a social process.-AI governance requires building in conflict and oversight mechanisms, mirroring human institutions, to ensure accountability and prevent unintended consequences.
            """.trimIndent()
        )

        val bulletLines = lines.filter { it.kind == SummaryDisplayLineKind.Bullet }

        assertEquals(3, bulletLines.size)
        assertEquals(
            "Frontier reasoning models spontaneously generate internal debates and conversations, exhibiting multi-perspective interactions within their own chain of thought.",
            bulletLines[0].text
        )
        assertEquals(
            "This conversational structure accounts for the models' accuracy advantage on hard reasoning tasks, suggesting that robust reasoning is a social process.",
            bulletLines[1].text
        )
        assertEquals(
            "AI governance requires building in conflict and oversight mechanisms, mirroring human institutions, to ensure accountability and prevent unintended consequences.",
            bulletLines[2].text
        )
    }
}
