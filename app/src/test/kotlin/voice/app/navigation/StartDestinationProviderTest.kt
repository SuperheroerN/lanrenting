package voice.app.navigation

import android.content.Intent
import androidx.datastore.core.DataStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import voice.app.MainActivity
import voice.core.data.BookId
import voice.navigation.Destination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class StartDestinationProviderTest {

  @Test
  fun `opens book overview by default`() = runTest {
    val provider = provider()
    val result = provider(Intent())
    assertEquals(listOf(Destination.BookOverview), result.destinations)
    assertFalse(result.shouldPlayCurrent)
  }

  @Test
  fun `opens playback when go-to-book extra is set`() = runTest {
    val bookId = BookId("content://book")
    val provider = provider(currentBook = bookId)
    val intent = Intent().putExtra(MainActivity.NI_GO_TO_BOOK, true)
    val result = provider(intent)
    assertEquals(
      listOf(Destination.BookOverview, Destination.Playback(bookId)),
      result.destinations,
    )
    assertFalse(result.shouldPlayCurrent)
  }

  @Test
  fun `playCurrent marks playback without playing during resolution`() = runTest {
    val bookId = BookId("content://book")
    val provider = provider(currentBook = bookId)
    val intent = Intent().setAction("playCurrent")
    val result = provider(intent)
    assertEquals(
      listOf(Destination.BookOverview, Destination.Playback(bookId)),
      result.destinations,
    )
    assertTrue(result.shouldPlayCurrent)
  }

  private fun provider(
    currentBook: BookId? = null,
  ): StartDestinationProvider {
    return StartDestinationProvider(
      currentBookStore = MemoryDataStore(currentBook),
    )
  }
}

private class MemoryDataStore<T>(initial: T) : DataStore<T> {
  private val value = MutableStateFlow(initial)
  override val data: Flow<T> get() = value
  override suspend fun updateData(transform: suspend (t: T) -> T): T {
    return value.updateAndGet { transform(it) }
  }
}
