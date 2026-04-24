package org.read.mobile

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReaderStorageRepositoryProgressTest {

    @Test
    fun readingProgressFor_sharesRemoteWebProgressAcrossUrlVariants() {
        val repository = freshRepository()
        val sourceWithQuery = "https://www.wsj.com/tech/ai/example-story?mod=hp_lead_pos7"
        val canonicalSource = "https://www.wsj.com/tech/ai/example-story"

        repository.saveReadingProgress(sourceWithQuery, blockIndex = 42, scrollOffset = 180)

        assertEquals(
            ReadingProgress(blockIndex = 42, scrollOffset = 180),
            repository.readingProgressFor(canonicalSource)
        )
    }

    @Test
    fun readingProgressFor_keepsCapturedTextProgressScopedToExactSource() {
        val repository = freshRepository()
        val firstCapture = "read-capture://captured/100"
        val secondCapture = "read-capture://captured/200"

        repository.saveReadingProgress(firstCapture, blockIndex = 7, scrollOffset = 24)

        assertEquals(
            ReadingProgress(blockIndex = 7, scrollOffset = 24),
            repository.readingProgressFor(firstCapture)
        )
        assertNull(repository.readingProgressFor(secondCapture))
    }

    private fun freshRepository(): ReaderStorageRepository {
        val application = RuntimeEnvironment.getApplication() as Application
        clearPrefs(application, "pdf_reader_history")
        clearPrefs(application, "pdf_reader_bookmarks")
        clearPrefs(application, "pdf_reader_reading_list")
        clearPrefs(application, "pdf_reader_progress")
        application.filesDir.resolve("document_cache").deleteRecursively()
        return ReaderStorageRepository(application)
    }

    private fun clearPrefs(application: Application, name: String) {
        application.getSharedPreferences(name, Application.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }
}
