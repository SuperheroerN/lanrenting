# Spec: 移除书源导入功能，内置 lrts 原生书源 + 密钥设置项

## 目标
1. 移除整个 .jdr/.js 书源导入功能（模块、UI、导航）。
2. 用 Kotlin 原生实现上传 JS 源的三个接口（搜索/获取章节/获取音频），内置进 app。
3. 音频密钥（JS 中硬编码 `AUDIO_KEY`）改为设置页手动配置的 DataStore 项，默认空；仅用于 `act=audio` 调用（与 JS 一致，搜索/章节不带 key）。

## 事实依据（来自 JS 源 l.js）
- SEARCH: `GET https://fm.12311111.xyz/api/lrts/index.php?act=search&keyword=X` → `{code:200, data:{list:[{id, bookTitle, bookImage, bookAnchor, bookDesc, count}]}}`，code!=200 报错。超时 15s。
- CHAPTERS: `GET https://m.lrts.me/ajax/getBookMenu?bookId=X&pageNum=N&pageSize=50` → `{apiStatus?, msg?, list:[{sectionId, name(去BOM), section, length}]}`；并发 5、最大 300 页、空页或 <50 条停止。超时 30s。无 key。
- AUDIO: `GET https://fm.12311111.xyz/api/lrts/index.php?act=audio&bookId=X&section=N&opType=1&key=KEY` → `{data:{url|error}}`；section 未知时扫描章节页映射 sectionId→section。超时 30s。
- Headers: 移动端 Chrome UA + `Accept: application/json`。

## 变更清单

### A. core/online（新增原生源）
1. `ExtensionOnlineSource.kt` 重命名为 `OnlineSourceBackend`（接口注释去掉 jdr 表述）。机械重命名，引用点：`OnlineSourceService.kt`、`OnlineSourceServiceTest.kt`。
2. 新增 `LrtsOnlineSource.kt`（`@Inject @SingleIn(AppScope)`，实现 `OnlineSourceBackend`）：
   - `handles(source) = source == "lrts"`；`enabledSourceInfos() = [OnlineSourceInfo("lrts", "懒人听书", true)]`；`hasEnabledSources() = true`（搜索/章节无需密钥，始终显示源）。
   - `search`/`chapters`/`resolveDirectUrl` 按 JS 逻辑用 OkHttp + kotlinx.serialization 实现；分页并发用 `async/awaitAll`（5 并发、300 页上限、空页/短页停止）。
   - `resolveDirectUrl`：先从 `OnlineChapterStore` 按 chapterId 查 `order`（章节列表已缓存时零网络），否则按 JS 的 orderOf 网络扫描；key 为空时抛 `OnlineSourceException("未配置密钥，请在设置中配置音频密钥")`。
   - API 客户端：注入 `@OnlineSourceStreamingClient` OkHttpClient，内部 `newBuilder().callTimeout(30s)` 用于 API 调用。
3. `OnlineSourceGraph.kt`：新增 `@LrtsApiKeyStore` 限定符（文件 `lrtsApiKey`，默认 ""）+ `@IntoSet` 提供 LrtsOnlineSource。
4. `OnlineSourceService.kt`：字段/方法改名 `extensionSources`→`backends`、`extensionBackendFor`→`backendFor`，更新注释；服务端（卡密/baseUrl）逻辑保持不动（当前设置隐藏、处于休眠，本次不动）。
5. 新增 `LrtsOnlineSourceTest`（MockWebServer）：搜索解析、章节分页+BOM、audio 带 key、缺 key 报错。

### B. 设置页（密钥手动配置）
6. `SettingsViewState`：新增 `lrtsApiKey: String`、`Dialog.LrtsApiKey`；preview 同步。
7. `SettingsViewModel`：注入 `@LrtsApiKeyStore DataStore<String>`；`onLrtsApiKeyRowClick()`、`lrtsApiKeyChanged(value)`（trim 后写 store，无验证流程）。
8. `SettingsListener`：新增上述两个方法。
9. `Settings.kt`：
   - 删除「扩展源」行（`openExtensions`）；
   - 新增可见行「音频密钥」（Display 值掩码/未设置），点击弹 `OnlineSourceTextDialog`（mask=true，无 verifying）。
10. strings：`values/strings.xml` 与 `values-zh-rCN/strings.xml` 新增 `settings.lrts_key.title/hint`；删除本次确认无引用的 `folder_add_type_source`、`folder_add_source_*`、`settings_extensions_*`、`extensions_*` 字符串。

### C. 移除导入源功能
11. `settings.gradle.kts` 删除 `:features:extensions`、`:core:extension-engine`、`:core:extension`；删除对应目录。
12. `app/build.gradle.kts`：移除 `projects.core.extension`、`projects.features.extensions` 依赖；清理 quickjs .so 相关注释/abiFilters 特判（如有）。
13. `features/folderPicker`：删除 `addcontent` 包全部文件；`build.gradle.kts` 移除 extension 两依赖。
14. `navigation/Destination.kt`：删除 `AddContent`、`ExtensionSources`。
15. `BookOverviewViewModel`：删除 `onAddBookClick()`；`BookOverview.kt` 移除该参数，`EmptyShelf` 的按钮改为 `onSearchActiveChange(true)`（跳搜索，直接搜在线书）。
16. `SettingsViewModel.openExtensions()` 删除。
17. `OnboardingExplanationViewModel`（模块不可达，仅为编译通过）：两处 `goTo(Destination.AddContent(...))` 改为 `navigator.setRoot(Destination.BookOverview)`。

## 不做的事
- 不删除服务端卡密在线源（OnlineSourceClient/CaptchaExtractor/token 等）——UI 已隐藏、处于休眠，本次范围外。
- 不删除 onboarding/webdav 模块。
- 不改签名、CI、依赖升级。

## 验证
1. `./gradlew :core:online:testDebugUnitTest :features:settings:testDebugUnitTest :features:bookOverview:testDebugUnitTest`
2. `./gradlew :app:testFreeDebugUnitTest`
3. `./gradlew :app:assembleFreeDebug` 产出 debug APK。
