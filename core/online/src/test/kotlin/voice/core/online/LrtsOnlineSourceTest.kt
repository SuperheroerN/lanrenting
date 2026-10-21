package voice.core.online

import androidx.datastore.core.DataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import voice.core.common.DispatcherProvider
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LrtsOnlineSourceTest {

  private val keyStore = FakeStore("")
  private val keyStatusStore = FakeStore(LrtsKeyStatus())
  private val chapterStore = OnlineChapterStore(createTempDirectory("lrts-chapters").toFile())
  private val dispatcherProvider = DispatcherProvider(
    UnconfinedTestDispatcher(),
    Dispatchers.IO,
    UnconfinedTestDispatcher(),
  )

  private fun source(): LrtsOnlineSource = LrtsOnlineSource(
    keyStore,
    keyStatusStore,
    OkHttpClient(),
    chapterStore,
    dispatcherProvider,
  )

  @Test
  fun `claims only the lrts source and exposes one chip`() = runTest {
    val source = source()
    assertTrue(source.handles(LrtsOnlineSource.SOURCE_NAME))
    assertEquals(false, source.handles("A"))
    assertEquals(
      listOf(OnlineSourceInfo(name = "lrts", displayName = "懒人听书", enabled = true)),
      source.enabledSourceInfos(),
    )
  }

  @Test
  fun `search maps the proxy response`() = runTest {
    keyStore.value = "test-key"
    val server = MockWebServer()
    server.start()
    server.enqueue(
      MockResponse.Builder().code(200).body(
        """
        {"code":200,"msg":"","data":{"list":[
          {"id":42,"bookTitle":"斗罗大陆","bookImage":"https://img/c.jpg","bookAnchor":"主播","bookDesc":"简介","count":120},
          {"id":null,"bookTitle":"跳过"}
        ]}}
        """.trimIndent(),
      ).build(),
    )

    val source = source()
    source.apiUrl = server.url("/api/lrts/index.php").toString()
    val results = source.search("lrts", "斗罗")

    assertEquals(1, results.size)
    val book = results.single()
    assertEquals("lrts", book.source)
    assertEquals("42", book.bookId)
    assertEquals("斗罗大陆", book.title)
    assertEquals("主播", book.author)
    assertEquals("https://img/c.jpg", book.cover)
    assertEquals("简介", book.intro)
    assertEquals(120, book.trackCount)

    val request = server.takeRequest()
    assertEquals("search", request.url.queryParameter("act"))
    assertEquals("斗罗", request.url.queryParameter("keyword"))
    assertEquals(null, request.url.queryParameter("key"))
    assertEquals("application/json", request.headers["Accept"])
    server.close()
  }

  @Test
  fun `a non-200 code fails with the server message`() = runTest {
    keyStore.value = "test-key"
    val server = MockWebServer()
    server.start()
    server.enqueue(MockResponse.Builder().code(200).body("""{"code":403,"msg":"无效请求"}""").build())

    val source = source()
    source.apiUrl = server.url("/api").toString()
    val e = assertFailsWith<OnlineSourceException> { source.search("lrts", "kw") }
    assertEquals("无效请求", e.message)
    server.close()
  }

  @Test
  fun `chapters paginate until a short page and strip the BOM`() = runTest {
    keyStore.value = "test-key"
    val server = MockWebServer()
    server.start()
    // within one batch the 5 page requests race, so which response lands on
    // which page is not deterministic: all responses of a batch are identical
    val full = (1..50).joinToString(separator = ",") { i ->
      """{"sectionId":$i,"name":"第${i}集","section":$i,"length":600}"""
    }
    repeat(5) {
      server.enqueue(MockResponse.Builder().code(200).body("""{"apiStatus":0,"list":[$full]}""").build())
    }
    val short = """{"sectionId":51,"name":"﻿第51集","section":51,"length":601}"""
    repeat(5) {
      server.enqueue(MockResponse.Builder().code(200).body("""{"apiStatus":0,"list":[$short]}""").build())
    }

    val source = source()
    source.chaptersApiUrl = server.url("/ajax/getBookMenu").toString()
    val chapters = source.chapters("lrts", "7")

    // batch 1 contributes 5 x 50, batch 2 stops at its first short page
    assertEquals(251, chapters.size)
    assertEquals("1", chapters.first().id)
    assertEquals("第1集", chapters.first().title)
    assertEquals(1, chapters.first().order)
    assertEquals(600, chapters.first().durationSeconds)
    assertEquals("51", chapters.last().id)
    // the BOM in front of the last title is stripped
    assertEquals("第51集", chapters.last().title)

    repeat(2) {
      val request = server.takeRequest()
      assertEquals("7", request.url.queryParameter("bookId"))
      assertEquals("50", request.url.queryParameter("pageSize"))
    }
    server.close()
  }

  @Test
  fun `a null list page ends the pagination instead of failing`() = runTest {
    keyStore.value = "test-key"
    val server = MockWebServer()
    server.start()
    val full = (1..50).joinToString(separator = ",") { i ->
      """{"sectionId":$i,"name":"第${i}集","section":$i,"length":600}"""
    }
    repeat(5) {
      server.enqueue(MockResponse.Builder().code(200).body("""{"apiStatus":0,"list":[$full]}""").build())
    }
    // past the last page the menu answers apiStatus 0 with a null list
    repeat(5) {
      server.enqueue(
        MockResponse.Builder().code(200).body(
          """{"apiStatus":0,"status":0,"msg":"","data":{"total":250},"list":null}""",
        ).build(),
      )
    }

    val source = source()
    source.chaptersApiUrl = server.url("/ajax/getBookMenu").toString()
    val chapters = source.chapters("lrts", "7")

    assertEquals(250, chapters.size)
    server.close()
  }

  @Test
  fun `a menu that only answers a null list fails with the empty message`() = runTest {
    keyStore.value = "test-key"
    val server = MockWebServer()
    server.start()
    repeat(5) {
      server.enqueue(MockResponse.Builder().code(200).body("""{"apiStatus":0,"list":null}""").build())
    }

    val source = source()
    source.chaptersApiUrl = server.url("/ajax/getBookMenu").toString()
    val e = assertFailsWith<OnlineSourceException> { source.chapters("lrts", "7") }
    assertEquals("该专辑没有章节", e.message)
    server.close()
  }

  @Test
  fun `a failed first menu page fails with the message`() = runTest {
    keyStore.value = "test-key"
    val server = MockWebServer()
    server.start()
    // the first batch races 5 page requests: answer every request with the error
    repeat(5) {
      server.enqueue(MockResponse.Builder().code(200).body("""{"apiStatus":1,"msg":"专辑不存在"}""").build())
    }

    val source = source()
    source.chaptersApiUrl = server.url("/ajax/getBookMenu").toString()
    val e = assertFailsWith<OnlineSourceException> { source.chapters("lrts", "7") }
    assertEquals("专辑不存在", e.message)
    server.close()
  }

  @Test
  fun `key info maps an active key`() = runTest {
    val server = MockWebServer()
    server.start()
    server.enqueue(
      MockResponse.Builder().code(200).body(
        """{"code":200,"msg":"查询成功","data":{"status":"active","valid_days":0,"activated":1,"expires_at":"2026-11-17 11:33:27","remain_sec":2379718,"remain_days":27.5}}""",
      ).build(),
    )

    val source = source()
    source.keyInfoUrl = server.url("/keyinfo.php").toString()
    val status = source.keyInfo("vip_abc")

    assertEquals("vip_abc", status.key)
    assertEquals(LrtsKeyStatus.STATE_OK, status.state)
    assertEquals("2026-11-17 11:33:27", status.expiresAt)
    assertEquals("", status.message)
    assertEquals("vip_abc", server.takeRequest().url.queryParameter("key"))
    server.close()
  }

  @Test
  fun `key info maps a not yet activated key as usable`() = runTest {
    val server = MockWebServer()
    server.start()
    server.enqueue(
      MockResponse.Builder().code(200).body(
        """{"code":200,"msg":"密钥有效，尚未激活，有效期自首次调用起 30 天","data":{"status":"not_activated","valid_days":30,"activated":0,"expires_at":null,"remain_sec":null,"remain_days":null}}""",
      ).build(),
    )

    val source = source()
    source.keyInfoUrl = server.url("/keyinfo.php").toString()
    val status = source.keyInfo("vip_new")

    assertEquals("vip_new", status.key)
    assertEquals(LrtsKeyStatus.STATE_OK, status.state)
    assertEquals("", status.expiresAt)
    assertEquals("密钥有效，尚未激活，有效期自首次调用起 30 天", status.message)
    server.close()
  }

  @Test
  fun `key info reports an invalid key`() = runTest {
    val server = MockWebServer()
    server.start()
    server.enqueue(MockResponse.Builder().code(200).body("""{"code":404,"msg":"密钥不存在"}""").build())

    val source = source()
    source.keyInfoUrl = server.url("/keyinfo.php").toString()
    val status = source.keyInfo("vip_bad")

    assertEquals("vip_bad", status.key)
    assertEquals(LrtsKeyStatus.STATE_INVALID, status.state)
    assertEquals("密钥不存在", status.message)
    server.close()
  }

  @Test
  fun `search without a configured key fails before any request`() = runTest {
    val source = source()
    val e = assertFailsWith<OnlineSourceException> { source.search("lrts", "kw") }
    assertEquals(LrtsOnlineSource.KEY_NOT_CONFIGURED, e.message)
  }

  @Test
  fun `chapters without a configured key fail before any request`() = runTest {
    val source = source()
    val e = assertFailsWith<OnlineSourceException> { source.chapters("lrts", "7") }
    assertEquals(LrtsOnlineSource.KEY_NOT_CONFIGURED, e.message)
  }

  @Test
  fun `an invalid verdict for the current key blocks the search`() = runTest {
    keyStore.value = "vip_bad"
    keyStatusStore.value = LrtsKeyStatus(key = "vip_bad", state = LrtsKeyStatus.STATE_INVALID)
    val source = source()
    val e = assertFailsWith<OnlineSourceException> { source.search("lrts", "kw") }
    assertEquals(LrtsOnlineSource.KEY_NOT_CONFIGURED, e.message)
  }

  @Test
  fun `audio resolve needs the configured key`() = runTest {
    val source = source()
    val e = assertFailsWith<OnlineSourceException> {
      source.resolveDirectUrl("lrts", "7", "10")
    }
    assertEquals(LrtsOnlineSource.KEY_NOT_CONFIGURED, e.message)
  }

  @Test
  fun `audio resolve takes the section from the stored chapters and sends the key`() = runTest {
    val server = MockWebServer()
    server.start()
    server.enqueue(MockResponse.Builder().code(200).body("""{"data":{"url":"https://cdn/a.mp3"}}""").build())
    val _ = chapterStore.update("lrts::7") {
      listOf(
        OnlineChapter(id = "9", title = "第9集", order = 9),
        OnlineChapter(id = "10", title = "第10集", order = 10),
      )
    }

    val source = source()
    keyStore.value = "test-key"
    source.apiUrl = server.url("/api/lrts/index.php").toString()
    val stream = source.resolveDirectUrl("lrts", "7", "10")

    assertEquals("https://cdn/a.mp3", stream?.url)
    val request = server.takeRequest()
    assertEquals("audio", request.url.queryParameter("act"))
    assertEquals("7", request.url.queryParameter("bookId"))
    // the section (10) comes from the stored chapter list, not the chapter id
    assertEquals("10", request.url.queryParameter("section"))
    assertEquals("1", request.url.queryParameter("opType"))
    assertEquals("test-key", request.url.queryParameter("key"))
    server.close()
  }

  @Test
  fun `audio resolve scans the menu when the order is unknown`() = runTest {
    val api = MockWebServer()
    api.start()
    val menu = MockWebServer()
    menu.start()
    api.enqueue(MockResponse.Builder().code(200).body("""{"data":{"url":"https://cdn/a.mp3"}}""").build())
    menu.enqueue(
      MockResponse.Builder().code(200).body(
        """{"apiStatus":0,"list":[{"sectionId":99,"name":"第1集","section":1,"length":60}]}""",
      ).build(),
    )

    val source = source()
    keyStore.value = "test-key"
    source.apiUrl = api.url("/api/lrts/index.php").toString()
    source.chaptersApiUrl = menu.url("/ajax/getBookMenu").toString()
    val stream = source.resolveDirectUrl("lrts", "7", "99")

    assertEquals("https://cdn/a.mp3", stream?.url)
    assertEquals("1", api.takeRequest().url.queryParameter("section"))
    assertEquals("7", menu.takeRequest().url.queryParameter("bookId"))
    api.close()
    menu.close()
  }

  @Test
  fun `an audio error payload fails with the error`() = runTest {
    val server = MockWebServer()
    server.start()
    server.enqueue(MockResponse.Builder().code(200).body("""{"data":{"error":"密钥无效"}}""").build())
    val _ = chapterStore.update("lrts::7") { listOf(OnlineChapter(id = "10", title = "第10集", order = 10)) }

    val source = source()
    keyStore.value = "test-key"
    source.apiUrl = server.url("/api").toString()
    val e = assertFailsWith<OnlineSourceException> { source.resolveDirectUrl("lrts", "7", "10") }
    assertEquals("密钥无效", e.message)
    server.close()
  }

  private class FakeStore<T>(initial: T) : DataStore<T> {

    private val state = MutableStateFlow(initial)

    var value: T
      get() = state.value
      set(v) {
        state.value = v
      }
    override val data: Flow<T> = state
    override suspend fun updateData(transform: suspend (T) -> T): T {
      state.value = transform(state.value)
      return state.value
    }
  }
}
