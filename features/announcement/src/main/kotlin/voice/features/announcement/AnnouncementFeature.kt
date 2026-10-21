package voice.features.announcement

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import voice.core.common.rootGraphAs
import voice.core.strings.R as StringsR

/**
 * Shows the announcement stored in the repository, hosted at the app level so
 * it appears on whatever screen the user is on.
 */
@Composable
fun AnnouncementFeature() {
  val shouldShowAnnouncement = remember { rootGraphAs<AnnouncementGraph>().shouldShowAnnouncement }
  var announcement by remember { mutableStateOf<VisibleAnnouncement?>(null) }
  val scope = rememberCoroutineScope()

  LaunchedEffect(Unit) {
    announcement = shouldShowAnnouncement.load()
  }

  val current = announcement ?: return

  AlertDialog(
    onDismissRequest = { announcement = null },
    title = { Text(stringResource(StringsR.string.announcement_dialog_title)) },
    text = {
      Text(
        text = current.text,
        modifier = Modifier.verticalScroll(rememberScrollState()),
      )
    },
    confirmButton = {
      TextButton(onClick = { announcement = null }) {
        Text(stringResource(StringsR.string.announcement_dialog_ok))
      }
    },
    dismissButton = {
      TextButton(
        onClick = {
          scope.launch { shouldShowAnnouncement.dismiss(current.fingerprint) }
          announcement = null
        },
      ) {
        Text(stringResource(StringsR.string.announcement_dialog_never))
      }
    },
  )
}
