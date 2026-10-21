package voice.core.online

import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.first

/**
 * Queries the key info endpoint and stores the verdict for the settings page
 * and the "configure the key" guard. A failed query (offline, rate limited)
 * keeps the previous verdict.
 */
@Inject
@SingleIn(AppScope::class)
public class LrtsKeyChecker(
  private val source: LrtsOnlineSource,
  @LrtsApiKeyStore private val keyStore: DataStore<String>,
  @LrtsKeyStatusStore private val statusStore: DataStore<LrtsKeyStatus>,
) {

  public suspend fun refresh() {
    val key = keyStore.data.first().trim()
    if (key.isBlank()) {
      statusStore.updateData { LrtsKeyStatus() }
      return
    }
    val status = runCatching { source.keyInfo(key) }.getOrNull() ?: return
    statusStore.updateData { status }
  }
}
