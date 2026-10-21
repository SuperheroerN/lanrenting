package voice.features.settings

import voice.core.data.ThemeColorScheme
import voice.core.data.ThemeMode
import voice.core.online.LrtsKeyStatus
import java.time.LocalTime

data class SettingsViewState(
  val themeMode: ThemeMode,
  val themeColorScheme: ThemeColorScheme,
  val showThemeColorSchemePref: Boolean,
  val seekTimeInSeconds: Int,
  val autoRewindInSeconds: Int,
  val dialog: Dialog?,
  val useGrid: Boolean,
  val autoSleepTimer: AutoSleepTimerViewState,
  val showAnalyticSetting: Boolean,
  val analyticsEnabled: Boolean,
  val showDeveloperMenu: Boolean,
  val autoRefreshWifi: Boolean,
  val btSkipToChapter: Boolean,
  val importParallelism: Int,
  val onlineSourceEnabled: Boolean,
  val onlineSourceBaseUrl: String,
  val onlineSourceCredential: String,
  val onlineSourceVerifying: Boolean = false,
  val onlineSourceVerifyError: Int? = null,
  /** The audio proxy key of the built-in lrts source, configured manually. */
  val lrtsApiKey: String = "",
  /** The last key info verdict: expiry shown on the settings row. */
  val lrtsKeyStatus: LrtsKeyStatus = LrtsKeyStatus(),
  val showSupportDevelopment: Boolean,
  val kioskMode: Boolean,
  /** Hidden for now: the row stays out of the list until this flips to true. */
  val showOnlineSource: Boolean = false,
) {

  enum class Dialog {
    AutoRewindAmount,
    SeekTime,
    ImportParallelism,
    OnlineSourceBaseUrl,
    OnlineSourceCredential,
    LrtsApiKey,
    LrtsKeyRestart,
    Theme,
    ColorScheme,
  }

  companion object {
    fun preview(): SettingsViewState {
      return SettingsViewState(
        themeMode = ThemeMode.FollowSystem,
        themeColorScheme = ThemeColorScheme.VoiceBlue,
        showThemeColorSchemePref = true,
        seekTimeInSeconds = 42,
        autoRewindInSeconds = 12,
        btSkipToChapter = true,
        importParallelism = 1,
        onlineSourceEnabled = false,
        onlineSourceBaseUrl = "",
        onlineSourceCredential = "",
        onlineSourceVerifying = false,
        onlineSourceVerifyError = null,
        dialog = null,
        useGrid = true,
        autoSleepTimer = AutoSleepTimerViewState.preview(),
        analyticsEnabled = false,
        showAnalyticSetting = true,
        showDeveloperMenu = true,
        autoRefreshWifi = true,
        showSupportDevelopment = true,
        kioskMode = false,
      )
    }
  }

  data class AutoSleepTimerViewState(
    val enabled: Boolean,
    val startTime: LocalTime,
    val endTime: LocalTime,
  ) {
    companion object {
      fun preview(): AutoSleepTimerViewState {
        return AutoSleepTimerViewState(
          enabled = false,
          startTime = LocalTime.of(22, 0),
          endTime = LocalTime.of(6, 0),
        )
      }
    }
  }
}
