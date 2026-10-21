package voice.features.announcement

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ShouldShowAnnouncementTest {

  private val dismissed = MemoryDataStore("")

  private fun subject(text: String?) = ShouldShowAnnouncement(
    dismissed = dismissed,
    source = AnnouncementSource { text },
  )

  @Test
  fun blankAnnouncementIsHidden() = runTest {
    assertNull(subject("   \n\t ").load())
  }

  @Test
  fun nullFetchIsHidden() = runTest {
    assertNull(subject(null).load())
  }

  @Test
  fun newAnnouncementIsShown() = runTest {
    val announcement = subject("Server maintenance tonight").load()
    assertNotNull(announcement)
    assertEquals("Server maintenance tonight", announcement.text)
  }

  @Test
  fun dismissedAnnouncementStaysHidden() = runTest {
    val announcement = subject("Server maintenance tonight").load()
    assertNotNull(announcement)
    subject("Server maintenance tonight").dismiss(announcement.fingerprint)

    assertNull(subject("Server maintenance tonight").load())
  }

  @Test
  fun editedAnnouncementIsShownAgain() = runTest {
    val first = subject("Server maintenance tonight").load()
    assertNotNull(first)
    subject("Server maintenance tonight").dismiss(first.fingerprint)

    val edited = subject("Server maintenance tonight!").load()
    assertNotNull(edited)
    assertEquals("Server maintenance tonight!", edited.text)
  }

  @Test
  fun surroundingWhitespaceDoesNotTriggerAgain() = runTest {
    val first = subject("Server maintenance tonight").load()
    assertNotNull(first)
    subject("Server maintenance tonight").dismiss(first.fingerprint)

    assertNull(subject("\n\n  Server maintenance tonight  \n").load())
  }

  @Test
  fun fingerprintFollowsContent() {
    assertEquals(fingerprintOf("a"), fingerprintOf("a"))
    assertEquals(16, fingerprintOf("a").length)
    assertNotEquals(fingerprintOf("a"), fingerprintOf("b"))
  }
}
