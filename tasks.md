# Tasks

- [x] 1. core/online：`ExtensionOnlineSource` 重命名为 `OnlineSourceBackend`，更新 `OnlineSourceService` 引用与注释
- [x] 2. core/online：新增 `LrtsOnlineSource.kt`（搜索/章节/音频原生实现 + 密钥 DataStore 注入）
- [x] 3. core/online：`OnlineSourceGraph` 新增 `@LrtsApiKeyStore` 与 `@IntoSet` 提供 LrtsOnlineSource
- [x] 4. core/online：新增 `LrtsOnlineSourceTest`（MockWebServer）
- [x] 5. 设置页：ViewState/ViewModel/Listener 新增音频密钥行与对话框；Settings.kt 删扩展源行、加密钥行
- [x] 6. strings：中英新增 `settings.lrts_key.*`；删除导入源/扩展源相关死字符串
- [x] 7. 移除：settings.gradle.kts 三模块 + 删目录 + app/folderPicker build 依赖 + Destination 两项 + addcontent 包
- [x] 8. BookOverview：删 onAddBookClick，EmptyShelf 按钮改为激活搜索；OnboardingExplanationViewModel 编译修复
- [x] 9. 验证：core/online、features/settings、features/bookOverview 单测 + :app:testFreeDebugUnitTest
- [x] 10. 构建：`./gradlew :app:assembleFreeDebug` 产出 debug APK
