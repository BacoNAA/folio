# Folio

随手记下生活里的细节，需要时找得到。

Folio 计划做一款私人生活记录应用，用文字、照片和声音留下那些容易忘记的小事：一家店值得再点的菜、旧照片背后的经历、护照放在哪个抽屉，或一件旧物的来历。

## 当前进度

项目已进入安卓开发，编辑、搜索与回收站页面仍待接入。具体后端范围、提交与合入状态、T1—T3 状态及待验收项统一见[当前开发进度](docs/engineering/开发流程.md#当前开发进度)。代码已合入不等于完整功能已验收。

当前 APK 可打开「随手记」并切换到「记录」。本机 Room 后端支持新建、续写和放弃文字草稿，也可按正文搜索并打开正式记录。编辑已有记录时，搜索和详情继续显示原文，完成后按原 ID 读取新正文。前端可通过 `LocalTextStore.get(context)` 获取 [TextDrafts 和 PublishedTextRecords](app/src/main/java/com/baconaa/folio/records/TextRecords.kt)，编辑与搜索页面待接入。

已按[整体需求审查](docs/requirements/整体需求审查.md)整理正文、子操作、共同规则和前置关系，并将已确认的功能边界写入正文。剩余范围与已确认结论索引集中见[待确认问题](docs/requirements/待确认问题.md)。

首个目标平台为安卓（Android），首版内容与技术主线均已确认。采用 Kotlin＋Jetpack Compose，数据使用 Room／SQLite，媒体复用现有组件；具体见[技术主线](docs/requirements/版本规划与审核事项.md#技术主线已确认)。首版面向 Android 10（API 29）及以上手机，基础功能不强制依赖 Google Play 服务；每轮可用版本通过已签名 APK 交付，见[设备兼容与交付](docs/requirements/版本规划与审核事项.md#设备兼容与交付已确认)。首版迭代顺序与范围已确认，最终界面方案随原型细化，应用商店上架和付费方案在发布前讨论。需求文档中的后续建议与视觉概念不代表已经实现或确定上线。

可通过[首版交互原型](docs/design/prototype.html)与[关键数据设计草案](docs/design/首版原型与关键数据设计.md)核对后续功能。浏览器原型使用内存示例。

照片原图通过 [LocalPhotoOriginals](app/src/main/java/com/baconaa/folio/media/LocalPhotoOriginals.kt) 导入，用返回的 `PhotoId` 读取原图。同一原图可以出现在记录的多个位置，每处都有自己的内容项 ID 和照片说明。

后端支持同一草稿内交错排列多段文字和照片。用 `saveDraft` 或 `appendPhotos` 起草，随后通过 [MixedContentDrafts](app/src/main/java/com/baconaa/folio/records/MixedContent.kt) 的 `insertText`、`insertPhoto` 追加或紧接指定项插入。`openMixedDraft` 一次读回完整顺序、当前项和光标。`completeMixedDraft` 提交后，`openRecordContent` 的 `items` 按相同顺序返回正式内容。

`moveItem` 按相邻位置移动一项，`removeItem` 和 `undoRemoval` 支持移除和恢复最近一项。草稿重开后可以通过 `canUndoRemoval` 查询是否还能撤销；移除后对其他项的修改会保留。

清单通过 `appendChecklist` 单独起草或追加，`insertChecklist` 在指定项后插入，`editChecklist` 保存条目文字及勾选状态。每条 `ChecklistEntry` 带稳定 ID，修改时保留该 ID。清单支持草稿重开、移动、移除撤销、完成、放弃和删除恢复；完成前列表和详情仍展示原正式版本。空清单不能独立起草或发布，清空已有项可以暂存草稿。Room v8 将清单内容保存在有序项中，旧文字、照片、事件时间及撤销项通过迁移保留。

应用内录音通过 [LocalAudioRecorder](app/src/main/java/com/baconaa/folio/media/LocalAudioRecorder.kt) 的 `start`、`stop` 和 `cancel` 控制。前端先申请麦克风权限，再在主线程调用控制器；`stop` 成功返回 `AudioClip`，原件已写入应用持久目录，并验证 AAC 音轨和实际时长。随后用 `appendAudio` 独立起草或追加，用 `insertAudio` 在指定项后插入，`editAudioCaption` 修改说明。只有草稿绑定成功后才能提示已保存到草稿；绑定失败时保留返回的片段 ID 重试。页面离开时应停止并保存或取消录制，尚在录制的片段不承诺中断恢复。前端权限、录音入口和生命周期接入仍待实现。

录音作为有序内容项保存，可与文字、照片和清单混排、移动、移除撤销，并随草稿一起完成或放弃。每处引用保留独立内容项 ID 和说明；时长由后端核验原件，不接受调用方估算值。`DraftPreview.totalAudioDurationMillis` 返回已保存录音项的总时长，无录音时为 `null`，可辨认纯录音草稿。Room v9 保留旧图文、清单、事件时间及撤销项，增加录音引用、时长和持久清理队列。当前正文搜索覆盖文字与清单，不检索录音中的话语。

试听使用进程内共享的 [LocalAudioPlayback](app/src/main/java/com/baconaa/folio/media/LocalAudioPlayback.kt)，`play` 新片段会停止上一段；`pause(id)`、`stop(id)` 只影响匹配片段，避免旧列表项误停新播放。控制方法在主线程调用，页面观察 `state` 展示播放或失败状态；统一的前台生命周期持有者负责停止和释放。按已确认技术主线使用平台 MediaRecorder 与 AndroidX Media3 ExoPlayer 1.11.1（Apache 2.0），仅引入基础播放器及其传递依赖，增加包体以复用解码和音频焦点管理；不引入播放器 UI。该版本最低 API 23、编译 API 36，符合本项目 API 29／37 配置。[Media3 发布说明](https://developer.android.com/jetpack/androidx/releases/media3)

事件时间通过 [RecordTimeEditing](app/src/main/java/com/baconaa/folio/records/RecordTime.kt) 保存。新建的 `saveDraft`、`appendPhotos` 可传 `initialEventTime`，随第一份实际内容原子落盘；参数仅在新建时生效。现场默认“现在”由前端选择并传入，补记或未确认照片时间可传 `null`，后端不推断。已有草稿用 `saveDraftEventTime` 修改或清空，`openDraftTimes` 读回；`openRecordContent.times` 返回最后完成的事件时间、首次记录时间和修改时间，回收站详情同样保留正式时间。日期与可选时刻保持事件原时区的当地表达。Room v7 增加事件时间字段，旧记录与草稿迁移后事件时间仍为空。

双时间浏览使用 [RecordBrowsing](app/src/main/java/com/baconaa/folio/records/RecordBrowsing.kt)：`listEventTimeline` 按当地年份及日期降序返回 `years`，未填写事件时间的记录单列在 `undated`；`listRecentRecords` 按首次记录时间降序返回全部有效正式记录，卡片继续保留事件时间。日期相同的记录沿用最近记录顺序作为稳定展示约定，不据此推断同日实际先后。首次记录时间相同时按入库顺序降序。新建草稿与回收站不进入两种列表，未完成修改不改变正式预览。

浏览卡片的 `preview` 按原顺序展示最多三个内容项，每项文字及媒体说明截取前 80 个 Unicode 码点；录音返回片段 ID 和时长供试听，清单只读展示前三条事项及全部事项、已完成事项数量。`PreviewContentItem` 与可保存的 `ContentItem` 分开，避免截断预览被回存为完整内容。`remainingItemCount`、`remainingPhotoCount` 和 `remainingAudioCount` 表示未展示的内容项及其中照片、录音项数量，空白文字不占预览，重复使用的媒体仍按各自内容项计数。详情通过同一 `RecordId` 调用 `openRecordContent`，读取完整内容及时间信息。当前列表为全量读取，尚未引入分页，前端列表与时间填写控件待接入。

删除与恢复使用 [RecordTrash](app/src/main/java/com/baconaa/folio/records/RecordTrash.kt)：`moveToTrash` 将正式记录和编辑草稿一起隐藏，`listTrash` 与 `openTrashRecord` 只展示上次完成的内容并通过 `hasDraft` 提示未完成修改；`restoreRecord` 用于立即撤销或从回收站恢复，两份内容保持独立。重复删除或恢复返回 `false`。页面负责删除确认、撤销提示和浏览位置，回收站与编辑页面均待接入。

用户确认后调用 `deletePermanently`，在同一事务中彻底删除回收站记录、编辑草稿和撤销项。完成、放弃、覆盖最近移除及彻底删除都会收集释放的媒体候选，提交后只删除已无任何引用的 Folio 文件；共享引用、回收站、隐藏草稿及可撤销项继续保护原件。`pendingPhotoCount`、`pendingAudioCount` 分别表示仍待清理的照片、录音数量，`null` 表示该类清理暂不可用；文件清理失败不改变已完成的记录删除。`retryPhotoCleanup`、`retryAudioCleanup` 可在重开应用后重试持久队列。清理不扫描尚未绑定的新导入照片或新录音，也不触及系统相册来源。

开发者和 AI 从[开发入口](AGENTS.md)定位本轮需求，按[开发流程](docs/engineering/开发流程.md)领卡、联调和验收。

## 构建和试用调试版

使用 JDK 17 和仓库的 Gradle Wrapper。Android SDK 需安装 `platforms;android-37.0`、`build-tools;36.0.0` 及 `platform-tools`。在 Android Studio 设置 SDK，或令 `ANDROID_HOME` 指向本机 SDK 目录。从仓库根目录在 PowerShell 执行以下命令。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

产物位于 `app/build/outputs/apk/debug/app-debug.apk`。连接 Android 10（API 29）及以上测试设备后，运行 `.\gradlew.bat :app:installDebug` 安装，再从桌面打开 Folio。运行 `.\gradlew.bat :app:connectedDebugAndroidTest` 验证导航、Room 保存与查询、照片、清单、录音与混排、删除恢复、事件时间与浏览及数据库迁移。录音测试会授予测试应用麦克风权限，并在前台录制短片段。macOS 和 Linux 使用 `bash ./gradlew` 和相同任务名。

[Android CI](.github/workflows/android.yml) 在面向 `main` 的 PR 和 `main` 推送时运行单测、组装调试包并上传 `folio-debug`。应用或构建配置变更的 PR，以及合入 `main` 后的构建，还在 API 29 模拟器执行设备集成测试。

## 已确定的方向

- 首版默认打开「随手记」，可以直接写一句、拍照、选图、录音或列清单。另一个一级入口「记录」用于按时间、地点或关键词找回正式记录，也可以从这里继续草稿。见[首版主流程与页面组织](docs/requirements/版本规划与审核事项.md#首版主流程与页面组织已确认)。
- 随手记录、随手保存，支持多种媒体形式，方便分类和找回。
- 第一版优先做好离线体验，不依赖联网完成基础使用。
- 第一版不包含 AI；识别、转写和自然语言找回留到后续，由用户选择使用。
- 先明确功能范围，再按必要性、难度与依赖安排多个开发迭代，每轮形成可使用的能力，保持统一的视觉风格与交互逻辑。
- 开发优先复用成熟开源项目、平台能力或已有服务，具体见[开发与技术选型原则](docs/requirements/产品方向与原则.md#开发与技术选型原则已确认)。

## 当前能力与分期

| 方面 | 已确认方向 |
|---|---|
| 记录 | 首版支持自由文字、照片、基础声音、基础清单、自由组合、完整内容草稿与完成后更新 |
| 整理 | 首版支持文字标签、星标、置顶及基础对象线索；图章、刻度、状态、情境组合、记录组与导览后续安排 |
| 找回 | 时间流、文字搜索、具体对象限定、组合筛选及人物物件线索 |
| 空间 | 首版包含地点命名与复用、可选坐标、地点列表和基础小册；地点体验、星图与真实地图后续安排 |
| 时间 | 首版包含双时间与模糊时间；生活章节、回看、对照与变化后续安排 |
| 数据保护 | 删除撤销与回收站；单条私密、完整备份与恢复后置 |

首版范围已按记录与维护、基础整理、浏览查找、基础地点浏览、删除恢复五方面确认，沿用此前已明确的保存反馈、草稿恢复与编辑补充范围。已确认先完成整体原型与关键数据设计，再依次开发文字与照片、录音与清单、整理与地点、完整时间与组合查找，见[首版迭代安排](docs/requirements/版本规划与审核事项.md#首版迭代安排已确认)。

基础清单进入首版。照片标注、复制、模板与合并拆分等增强均已保留，本轮未纳入已确认首版范围，具体批次继续细化。同一项信息在模板与标记中共用。

当前优先基础能力。字体、动效、操作替代和完整状态体验后续完善；应用锁与备份提醒等暂缓。地点星图、真实地图、生活章节、记录组与可选导览已安排在后续。收藏展示以记录组和导览为基础，3D 保留为后续呈现探索，独立建模等额外能力另议。

## 需求文档

从 [Folio 需求总索引](docs/Folio-需求与设计文档-v0.1.md)查看当前结果和未决事项，再进入相应能力文档。

现存需求编号用于定位正文，数量不代表独立功能数或开发进度。审核统计只在总索引维护；[整体需求审查](docs/requirements/整体需求审查.md)维护能力关系，[待确认问题](docs/requirements/待确认问题.md)集中维护未决范围，[工作分工](docs/requirements/协作分工.md)说明共同交付要求。归属同一能力不要求所有增强同时实现；去留已确认也不代表可以跳过范围确认。

核心功能边界、P01—P09 九条产品原则、首版内容、五项技术主线、设备兼容与交付及首版主流程均已确认。工程起步后的首批任务是文字草稿与正式记录查找。按相关事项批量讨论，确认结果集中更新文档；使用示例只帮助理解已有功能。
