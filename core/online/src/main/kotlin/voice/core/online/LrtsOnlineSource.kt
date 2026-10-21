package voice.core.online

import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import voice.core.common.DispatcherProvider
import java.util.concurrent.TimeUnit

/**
 * Built-in 懒人听书 (lrts) source, the native port of the former JS extension:
 * search and audio resolve run through the fm.12311111.xyz proxy, the chapter
 * menu comes straight from m.lrts.me. Everything online needs the key the user
 * configures in the settings; without it (or with a verdict-invalid one) the
 * online source fails with a "configure the key" message.
 */
@Inject
@SingleIn(AppScope::class)
public class LrtsOnlineSource(
  @LrtsApiKeyStore private val apiKeyStore: DataStore<String>,
  @LrtsKeyStatusStore private val keyStatusStore: DataStore<LrtsKeyStatus>,
  @OnlineSourceStreamingClient httpClient: OkHttpClient,
  private val chapterStore: OnlineChapterStore,
  private val dispatcherProvider: DispatcherProvider,
) : OnlineSourceBackend {

  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
  }

  /** The streaming client has no call timeout on purpose; API calls must finish. */
  private val apiClient = httpClient.newBuilder()
    .callTimeout(30, TimeUnit.SECONDS)
    .build()

  /** Overridable so tests can point the source at a local mock server. */
  internal var apiUrl: String = API_URL
  internal var chaptersApiUrl: String = CHAPTERS_API
  internal var keyInfoUrl: String = KEYINFO_URL

  override fun handles(source: String): Boolean = source == SOURCE_NAME

  override suspend fun hasEnabledSources(): Boolean = true

  override suspend fun enabledSourceInfos(): List<OnlineSourceInfo> {
    return listOf(OnlineSourceInfo(name = SOURCE_NAME, displayName = DISPLAY_NAME, enabled = true))
  }

  override suspend fun search(
    source: String,
    keyword: String,
  ): List<OnlineSearchResult> {
    val _ = requireUsableKey()
    val url = apiUrl.toHttpUrl().newBuilder()
      .addQueryParameter("act", "search")
      .addQueryParameter("keyword", keyword)
      .build()
    val body = apiGet(url)
    val response = decode<SearchResponse>(body)
    if (response.code != 200) {
      throw OnlineSourceException(response.msg?.takeIf { it.isNotBlank() } ?: "搜索失败")
    }
    return response.data?.list.orEmpty()
      .filter { !it.id.isNullOrBlank() }
      .map {
        OnlineSearchResult(
          source = SOURCE_NAME,
          bookId = it.id!!,
          title = it.bookTitle.orEmpty(),
          author = it.bookAnchor.orEmpty(),
          cover = it.bookImage.orEmpty(),
          intro = it.bookDesc.orEmpty(),
          trackCount = it.count ?: 0,
        )
      }
  }

  override suspend fun chapters(
    source: String,
    bookId: String,
  ): List<OnlineChapter> {
    if (bookId.isBlank()) throw OnlineSourceException("缺少 bookId")
    val _ = requireUsableKey()
    val out = ArrayList<OnlineChapter>()
    var start = 1
    while (start <= MAX_PAGE) {
      val pages = coroutineScope {
        (start until start + CONCURRENCY)
          .map { page -> async { fetchChapterPage(bookId, page) } }
          .awaitAll()
      }
      var stop = false
      for (list in pages) {
        if (list.isEmpty()) {
          stop = true
          break
        }
        out += list
        if (list.size < PAGE_SIZE) {
          stop = true
          break
        }
      }
      if (stop) break
      start += CONCURRENCY
    }
    if (out.isEmpty()) throw OnlineSourceException("该专辑没有章节")
    return out
  }

  override suspend fun resolveDirectUrl(
    source: String,
    bookId: String,
    chapterId: String,
  ): ResolvedStream? {
    if (bookId.isBlank() || chapterId.isBlank()) throw OnlineSourceException("缺少 bookId / chapterId")
    val key = requireUsableKey()

    val section = storedOrder(source, bookId, chapterId)
      ?: scanOrder(bookId, chapterId)
      ?: throw OnlineSourceException("未找到章节 $chapterId 对应的集序号")

    val url = apiUrl.toHttpUrl().newBuilder()
      .addQueryParameter("act", "audio")
      .addQueryParameter("bookId", bookId)
      .addQueryParameter("section", section.toString())
      .addQueryParameter("opType", "1")
      .addQueryParameter("key", key)
      .build()
    val body = apiGet(url)
    val data = decode<AudioResponse>(body).data
    if (data?.error?.isNotBlank() == true) throw OnlineSourceException(data.error)
    val streamUrl = data?.url?.takeIf { it.isNotBlank() } ?: throw OnlineSourceException("未找到音频URL")
    return ResolvedStream(streamUrl)
  }

  /**
   * Queries the key info endpoint. Returns the verdict for the given key.
   * Only a rate-limited or broken query throws, so the caller keeps the old
   * verdict; a definite "no such key" answer comes back as STATE_INVALID.
   */
  public suspend fun keyInfo(key: String): LrtsKeyStatus {
    if (key.isBlank()) return LrtsKeyStatus()
    val url = keyInfoUrl.toHttpUrl().newBuilder()
      .addQueryParameter("key", key)
      .build()
    val body = apiGet(url)
    val response = decode<KeyInfoResponse>(body)
    if (response.code == 200) {
      val data = response.data
      // a not-yet-activated key is valid too: it starts counting down on first use,
      // so it must not be blocked, otherwise search/playback could never activate it
      val active = data?.status == "active" || data?.status == "not_activated"
      return LrtsKeyStatus(
        key = key,
        state = if (active) LrtsKeyStatus.STATE_OK else LrtsKeyStatus.STATE_INVALID,
        expiresAt = data?.expiresAt.orEmpty(),
        message = if (data?.status == "active") "" else response.msg.orEmpty(),
      )
    }
    // rate limited: the answer carries a retry hint instead of a verdict
    if (response.data?.retryAfterSec != null) {
      throw OnlineSourceException(response.msg?.takeIf { it.isNotBlank() } ?: "查询太频繁")
    }
    return LrtsKeyStatus(
      key = key,
      state = LrtsKeyStatus.STATE_INVALID,
      message = response.msg.orEmpty(),
    )
  }

  /**
   * The key every online interaction requires: blank or a verdict-invalid key
   * fails with the same "configure the key" message before any request is made.
   */
  private suspend fun requireUsableKey(): String {
    val key = apiKeyStore.data.first().trim()
    if (key.isBlank()) throw OnlineSourceException(KEY_NOT_CONFIGURED)
    val status = runCatching { keyStatusStore.data.first() }.getOrNull() ?: LrtsKeyStatus()
    if (status.state == LrtsKeyStatus.STATE_INVALID && status.key == key) {
      throw OnlineSourceException(KEY_NOT_CONFIGURED)
    }
    return key
  }

  /** The chapter order from the locally stored chapter list, when it is cached. */
  private suspend fun storedOrder(
    source: String,
    bookId: String,
    chapterId: String,
  ): Int? {
    return runCatching { chapterStore.chapters("$source::$bookId") }
      .getOrNull()
      ?.firstOrNull { it.id == chapterId }
      ?.order
      ?.takeIf { it > 0 }
  }

  /** Walks the chapter pages one by one until the chapter id shows up. */
  private suspend fun scanOrder(
    bookId: String,
    chapterId: String,
  ): Int? {
    for (page in 1..MAX_PAGE) {
      val list = fetchChapterPage(bookId, page)
      if (list.isEmpty()) break
      for (chapter in list) {
        if (chapter.id == chapterId) return chapter.order
      }
    }
    return null
  }

  private suspend fun fetchChapterPage(
    bookId: String,
    page: Int,
  ): List<OnlineChapter> {
    val url = chaptersApiUrl.toHttpUrl().newBuilder()
      .addQueryParameter("bookId", bookId)
      .addQueryParameter("pageNum", page.toString())
      .addQueryParameter("pageSize", PAGE_SIZE.toString())
      .build()
    val body = apiGet(url)
    val response = decode<MenuResponse>(body)
    if ((response.apiStatus ?: 0) != 0) {
      if (page == 1) throw OnlineSourceException(response.msg ?: "获取章节失败")
      return emptyList()
    }
    return response.list.orEmpty().mapNotNull { item ->
      val sectionId = item.sectionId?.toString() ?: return@mapNotNull null
      OnlineChapter(
        id = sectionId,
        title = item.name.orEmpty().removePrefix(BOM),
        order = item.section ?: 0,
        durationSeconds = item.length ?: 0,
      )
    }
  }

  /** A blocking GET on the io dispatcher; a non-2xx answer fails with a message. */
  private suspend fun apiGet(url: HttpUrl): String {
    val request = Request.Builder()
      .url(url)
      .header("User-Agent", USER_AGENT)
      .header("Accept", "application/json")
      .build()
    return withContext(dispatcherProvider.io) {
      apiClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw OnlineSourceException("HTTP ${response.code}")
        response.body.string()
      }
    }
  }

  private inline fun <reified T> decode(body: String): T {
    return runCatching { json.decodeFromString<T>(body) }
      .getOrElse { throw OnlineSourceException("响应解析失败: ${it.message}") }
  }

  @Serializable
  private data class SearchResponse(
    val code: Int = 0,
    val msg: String? = null,
    val data: SearchData? = null,
  )

  @Serializable
  private data class SearchData(val list: List<SearchItem>? = null)

  @Serializable
  private data class SearchItem(
    val id: String? = null,
    val bookTitle: String? = null,
    val bookImage: String? = null,
    val bookAnchor: String? = null,
    val bookDesc: String? = null,
    val count: Int? = null,
  )

  @Serializable
  private data class MenuResponse(
    val apiStatus: Int? = null,
    val msg: String? = null,
    // the menu answers "list":null past the last page; treat it as an empty page
    val list: List<MenuItem>? = null,
  )

  @Serializable
  private data class MenuItem(
    val sectionId: Long? = null,
    val name: String? = null,
    val section: Int? = null,
    val length: Int? = null,
  )

  @Serializable
  private data class AudioResponse(val data: AudioData? = null)

  @Serializable
  private data class AudioData(
    val url: String? = null,
    val error: String? = null,
  )

  @Serializable
  private data class KeyInfoResponse(
    val code: Int = 0,
    val msg: String? = null,
    val data: KeyInfoData? = null,
  )

  @Serializable
  private data class KeyInfoData(
    val status: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("retry_after_sec") val retryAfterSec: Int? = null,
  )

  public companion object {
    public const val SOURCE_NAME: String = "lrts"
    public const val DISPLAY_NAME: String = "懒人听书"
    public const val KEY_NOT_CONFIGURED: String = "请先配置密钥"

    private const val API_URL = "https://fm.12311111.xyz/api/lrts/index.php"
    private const val CHAPTERS_API = "https://m.lrts.me/ajax/getBookMenu"
    private const val KEYINFO_URL = "https://fm.12311111.xyz/keyinfo.php"
    private const val USER_AGENT =
      "Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    private const val PAGE_SIZE = 50
    private const val CONCURRENCY = 5
    private const val MAX_PAGE = 300
    private const val BOM = "﻿"
  }
}
