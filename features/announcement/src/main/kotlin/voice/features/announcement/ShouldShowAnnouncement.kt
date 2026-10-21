package voice.features.announcement

import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.first
import voice.core.data.store.UpdateDismissedStore
import voice.core.logging.api.Logger

/** An announcement the user has not dismissed yet. */
data class VisibleAnnouncement(
  val text: String,
  val fingerprint: String,
)

@Inject
class ShouldShowAnnouncement(
  @UpdateDismissedStore private val dismissed: DataStore<String>,
  private val source: AnnouncementSource,
) {

  /**
   * Returns the announcement to show, or null when there is nothing to show:
   * the text is empty, the fetch failed, or the user already dismissed this
   * exact content.
   */
  suspend fun load(): VisibleAnnouncement? {
    val raw = try {
      source.fetch()
    } catch (e: Exception) {
      Logger.w(e, "Could not load the announcement")
      null
    } ?: return null

    val text = raw.trim()
    if (text.isEmpty()) return null

    val fingerprint = fingerprintOf(text)
    if (dismissed.data.first() == fingerprint) return null

    return VisibleAnnouncement(text = text, fingerprint = fingerprint)
  }

  /** Remembers this exact content so it is not shown again. */
  suspend fun dismiss(fingerprint: String) {
    dismissed.updateData { fingerprint }
  }
}

/**
 * Identifies the announcement by its content, so any edit - even a single
 * character - makes it show up again.
 */
internal fun fingerprintOf(text: String): String {
  val digest = java.security.MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8))
  return digest.take(8).joinToString(separator = "") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
  }
}

@ContributesTo(AppScope::class)
interface AnnouncementGraph {

  val shouldShowAnnouncement: ShouldShowAnnouncement

  @Provides
  @SingleIn(AppScope::class)
  fun announcementSource(): AnnouncementSource = RemoteAnnouncementSource()
}
