package voice.features.onboarding.explanation

import androidx.datastore.core.DataStore
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import voice.core.common.AppInfoProvider
import voice.core.data.store.AnalyticsConsentStore
import voice.navigation.Destination
import voice.navigation.Navigator

@Inject
class OnboardingExplanationViewModel(
  private val navigator: Navigator,
  @AnalyticsConsentStore
  private val analyticsConsentStore: DataStore<Boolean>,
  private val appInfoProvider: AppInfoProvider,
) {

  private val scope = MainScope()

  fun viewState(): OnboardingExplanationViewState {
    return OnboardingExplanationViewState(
      askForAnalytics = appInfoProvider.analyticsIncluded,
    )
  }

  fun onContinueWithAnalytics() {
    scope.launch {
      analyticsConsentStore.updateData { true }
    }
    navigator.setRoot(Destination.BookOverview)
  }

  fun onContinueWithoutAnalytics() {
    scope.launch {
      analyticsConsentStore.updateData { false }
    }
    navigator.setRoot(Destination.BookOverview)
  }

  fun onPrivacyPolicyClick() {
    navigator.goTo(Destination.Website("https://github.com/cq10086123/Timbre/blob/main/PRIVACY.md"))
  }

  fun onClose() {
    navigator.goBack()
  }
}
