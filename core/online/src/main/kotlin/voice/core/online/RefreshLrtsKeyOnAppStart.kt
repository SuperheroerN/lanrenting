package voice.core.online

import android.app.Application
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import voice.core.common.DispatcherProvider
import voice.core.initializer.AppInitializer
import voice.core.logging.api.Logger

/**
 * Refreshes the lrts key verdict once in the background at app start, so the
 * settings page shows the expiry without the user opening it first.
 */
@ContributesIntoSet(AppScope::class)
@Inject
public class RefreshLrtsKeyOnAppStart(
  private val keyChecker: LrtsKeyChecker,
  dispatcherProvider: DispatcherProvider,
) : AppInitializer {

  private val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.io)

  override fun onAppStart(application: Application) {
    scope.launch {
      runCatching { keyChecker.refresh() }
        .onFailure { Logger.w("Refreshing the lrts key info failed: $it") }
    }
  }
}
