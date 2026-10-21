package voice.features.announcement

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Reads the raw announcement text. Returns null when it cannot be read. */
fun interface AnnouncementSource {
  suspend fun fetch(): String?
}

class RemoteAnnouncementSource(private val client: OkHttpClient = defaultClient()) : AnnouncementSource {

  override suspend fun fetch(): String? = withContext(Dispatchers.IO) {
    for (url in urls) {
      val text = bodyOrNull(url)
      if (text != null) return@withContext text
    }
    null
  }

  private fun bodyOrNull(url: String): String? = runCatching {
    client.newCall(Request.Builder().url(url).build()).execute().use { response ->
      if (response.isSuccessful) response.body.string().orEmpty() else null
    }
  }.getOrNull()

  companion object {

    /**
     * raw.githubusercontent.com serves the file immediately, which is what the
     * "edit the file, see it again" promise needs. The jsDelivr mirrors cache
     * `@main` for hours, so they are only a fallback for networks where GitHub
     * is unreachable - there a stale announcement is better than none.
     */
    private val urls = listOf(
      "https://raw.githubusercontent.com/SuperheroerN/lanrenting/main/announcement.txt",
      "https://fastly.jsdelivr.net/gh/SuperheroerN/lanrenting@main/announcement.txt",
      "https://cdn.jsdelivr.net/gh/SuperheroerN/lanrenting@main/announcement.txt",
    )

    /** The announcement is a few hundred bytes, so a short timeout is enough. */
    fun defaultClient(): OkHttpClient {
      return OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()
    }
  }
}
