# Folio

随手记下生活里的细节，需要时找得到。

Folio 计划做一款私人生活记录应用，用文字、照片和声音留下那些容易忘记的小事：一家店值得再点的菜、旧照片背后的经历、护照放在哪个抽屉，或一件旧物的来历。

## 当前进度

当前开发分支已接入安卓文字、拍照与选图、录音、清单和混合编辑页面，以及草稿恢复、记录浏览、搜索和详情；已接入精确事件时间、文字标签、星标置顶、删除撤销和回收站、地点及可选坐标、基础地点小册与最近经历摘要，支持人物／物件线索、别名和具体对象查找。搜索可按内容类型组合筛选。后端可按事件时间范围组合查找，分别返回明确和可能结果。记录流与地点小册可展示模糊时间和自定义季节。具体范围、合入状态、T1—T3 状态及待验收项统一见[当前开发进度](docs/engineering/开发流程.md#当前开发进度)。

从「随手记」的写一句、拍照、选图、录音或清单开始记录，内容自动存入本机草稿；从「记录 → 继续草稿」重开，单份直接续写，多份按最近编辑顺序选择。新建点「完成」后回首页，经「已保存 · 查看」打开详情；搜索只返回正式版本。已有记录可编辑和放弃，完成后回到原记录详情，再返回原浏览或搜索位置。失败时保留输入并显示重试入口，空白记录不能完成。

编辑页按顺序展示文字、照片、录音和清单。「＋ 添加内容」追加到末尾；每项「⋯」支持在此后添加、相邻移动、移除与撤销，重开草稿仍可撤销最近移除。照片复制原件后加入草稿，可重复添加多张，点图可放大；清单支持增删事项与勾选。录音按需申请麦克风权限，拒绝后仍可编辑其他内容；可取消或停止并保存、试听和填写说明。离开编辑或进入后台会停止录制并保存有效片段，后台不继续长录音。媒体绑定失败保留原件 ID 重试，只有加入草稿成功才显示保存完成；导入失败可重试或放弃这张照片继续编辑。

编辑页末尾展开「时间、地点与线索」可填写日期、可选时刻及时区，或清空事件时间；现场新建默认现在，从首页导入相册照片默认留空。时间随实际内容存入草稿，完成前不改变正式记录。文字标签可选择已有标签、新建或改名，同名标签保持独立；新标签随记录完成后才供其他记录选用，共享标签改名会先说明影响范围并立即同步引用。标签需要先有实际内容，整理信息不是保存前提。

地点可从记录编辑中搜索选择、新建或取消，城市、地址和区别备注分别选填，同名地点保持独立身份。新地点和选择随草稿保存，完成后才影响正式地点及记录归属。「记录 → 按地点」可按名称、城市、地址和备注找到地点，进入小册按事件时间或最近记录浏览，并结合关键词、标签和星标查找；移除地点条件保留关键词。小册的「地点操作」支持补记一条、关联已有记录，以及独立保存共享资料；共享资料更新不提交记录草稿，不改变记录时间。零记录地点仍可复用。

新建或编辑地点时可主动「获取当前位置」，按需授权后显示坐标、大致位置提示及系统提供的误差范围。先「使用此坐标」，再「保存地点」才写入；也可取消或清除坐标。定位失败可重试，不妨碍保存无坐标地点；关闭表单或进入后台停止请求。当前坐标不会替换名称或地址，也不会自动填入补记旧事。地点列表的最近经历仅依据有效正式记录的事件时间；未定时间、重叠日期范围或无法确定先后时明确提示不确定，首次记录时间和置顶不参与判断。当前不提供地图。

详情可加星、置顶或删除；标记立即生效且不改变内容修改时间。「记录 → 星标」集中查看，搜索可结合关键词、标签多选任一和星标限定，清除筛选保留搜索词。删除后返回原入口并可撤销，正式内容与未完成修改一起进出回收站。回收站只读查看上次正式内容，恢复后留在回收站并提供查看入口；彻底删除再次确认，一并删除正式内容和草稿。媒体文件清理失败时可重试，重开回收站也会重试待清理文件。

人物与物件可以在记录中分别新建或多选，只填名称即可，区别说明和别名可选（每行一个别名）。查找对象匹配名称、说明及别名，同名按身份区分。新对象随草稿保存，完成后共享，未选中的新对象或放弃草稿时丢弃；已有共享对象即使零记录仍可选用。编辑共享资料前提示影响所有引用，保存不提交记录草稿、不改变记录时间。搜索工具栏可横向滚动选择人物和物件，同类满足任一个、不同类别同时满足；各条件可单独移除，保留搜索词和其他条件。记录卡片的对象按钮可打开该对象的正式记录结果。

页面使用 `FolioViewModel` 持有状态，通过 `LocalTextStore.get(context)` 调用 [TextDrafts 和 PublishedTextRecords](app/src/main/java/com/baconaa/folio/records/TextRecords.kt)、[MixedContentDrafts](app/src/main/java/com/baconaa/folio/records/MixedContent.kt) 等接口。草稿写入串行，完成及离开编辑等待最后输入保存成功；读写错误与空结果分别展示。结构操作提交后重读失败时，只重读而不重复插入。记录列表可切换事件时间和最近记录，未填写事件时间的记录单列。安卓端待接入模糊时间填写、季节设置、时间范围筛选和选填标题。

已按[整体需求审查](docs/requirements/整体需求审查.md)整理正文、子操作、共同规则和前置关系，并将已确认的功能边界写入正文。剩余范围与已确认结论索引集中见[待确认问题](docs/requirements/待确认问题.md)。

首个目标平台为安卓（Android），首版内容与技术主线均已确认。采用 Kotlin＋Jetpack Compose，数据使用 Room／SQLite，媒体复用现有组件；具体见[技术主线](docs/requirements/版本规划与审核事项.md#技术主线已确认)。首版面向 Android 10（API 29）及以上手机，基础功能不强制依赖 Google Play 服务；每轮可用版本通过已签名 APK 交付，见[设备兼容与交付](docs/requirements/版本规划与审核事项.md#设备兼容与交付已确认)。首版迭代顺序与范围已确认，最终界面方案随原型细化，应用商店上架和付费方案在发布前讨论。需求文档中的后续建议与视觉概念不代表已经实现或确定上线。

可通过[首版交互原型](docs/design/prototype.html)与[关键数据设计草案](docs/design/首版原型与关键数据设计.md)核对后续功能。浏览器原型使用内存示例。

照片原图通过 [LocalPhotoOriginals](app/src/main/java/com/baconaa/folio/media/LocalPhotoOriginals.kt) 导入，用返回的 `PhotoId` 读取原图。同一原图可以出现在记录的多个位置，每处都有自己的内容项 ID 和照片说明。

导入后可调用 `readCaptureTime(photoId)`，从原图的 EXIF 原始拍摄标签读取当地日期、时刻和可用的 UTC 偏移。没有拍摄时间时返回 `null`，缺少偏移时保留当地时间。Android 10 的平台接口仅读取当地时间。该接口提供可供用户确认的候选值，记录的事件时间仍由草稿保存接口写入；相册确认页面待接入。

后端支持同一草稿内交错排列多段文字和照片。用 `saveDraft` 或 `appendPhotos` 起草，随后通过 [MixedContentDrafts](app/src/main/java/com/baconaa/folio/records/MixedContent.kt) 的 `insertText`、`insertPhoto` 追加或紧接指定项插入。`openMixedDraft` 一次读回完整顺序、当前项和光标。`completeMixedDraft` 提交后，`openRecordContent` 的 `items` 按相同顺序返回正式内容。

`moveItem` 按相邻位置移动一项，`removeItem` 和 `undoRemoval` 支持移除和恢复最近一项。草稿重开后可以通过 `canUndoRemoval` 查询是否还能撤销；移除后对其他项的修改会保留。

清单通过 `appendChecklist` 单独起草或追加，`insertChecklist` 在指定项后插入，`editChecklist` 保存条目文字及勾选状态。每条 `ChecklistEntry` 带稳定 ID，修改时保留该 ID。清单支持草稿重开、移动、移除撤销、完成、放弃和删除恢复；完成前列表和详情仍展示原正式版本。空清单不能独立起草或发布，清空已有项可以暂存草稿。Room v8 将清单内容保存在有序项中，旧文字、照片、事件时间及撤销项通过迁移保留。

应用内录音通过 [LocalAudioRecorder](app/src/main/java/com/baconaa/folio/media/LocalAudioRecorder.kt) 的 `start`、`stop` 和 `cancel` 控制。前端先申请麦克风权限，再在主线程调用控制器；`stop` 成功返回 `AudioClip`，原件已写入应用持久目录，并验证 AAC 音轨和实际时长。随后用 `appendAudio` 独立起草或追加，用 `insertAudio` 在指定项后插入，`editAudioCaption` 修改说明。只有草稿绑定成功后才能提示已保存到草稿；绑定失败时保留返回的片段 ID 重试。前端权限、录音入口和生命周期已接入，尚在录制且未保存的片段不承诺进程被强制终止后的恢复。

录音作为有序内容项保存，可与文字、照片和清单混排、移动、移除撤销，并随草稿一起完成或放弃。每处引用保留独立内容项 ID 和说明；时长由后端核验原件，不接受调用方估算值。`DraftPreview.totalAudioDurationMillis` 返回已保存录音项的总时长，无录音时为 `null`，可辨认纯录音草稿。Room v9 保留旧图文、清单、事件时间及撤销项，增加录音引用、时长和持久清理队列。录音不做语音转写，手动填写的说明可用于查找。

试听使用进程内共享的 [LocalAudioPlayback](app/src/main/java/com/baconaa/folio/media/LocalAudioPlayback.kt)，`play` 新片段会停止上一段；`pause(id)`、`stop(id)` 只影响匹配片段，避免旧列表项误停新播放。控制方法在主线程调用，页面观察 `state` 展示播放或失败状态；统一的前台生命周期持有者负责停止和释放。按已确认技术主线使用平台 MediaRecorder 与 AndroidX Media3 ExoPlayer 1.11.1（Apache 2.0），仅引入基础播放器及其传递依赖，增加包体以复用解码和音频焦点管理；不引入播放器 UI。该版本最低 API 23、编译 API 36，符合本项目 API 29／37 配置。[Media3 发布说明](https://developer.android.com/jetpack/androidx/releases/media3)

事件时间通过 [RecordTimeEditing](app/src/main/java/com/baconaa/folio/records/RecordTime.kt) 保存。新建的 `saveDraft`、`appendPhotos` 可传 `initialEventTime`，随第一份实际内容原子落盘；参数仅在新建时生效。现场默认“现在”由前端选择并传入，补记或未确认照片时间可传 `null`，后端不推断。已有草稿用 `saveDraftEventTime` 修改或清空，`openDraftTimes` 读回；`openRecordContent.times` 返回最后完成的事件时间、首次记录时间和修改时间，回收站详情同样保留正式时间。Room v15 保留 v14 已有的精确日期、时刻、原时区和留空值，新增年、月、季节、日期范围和可附范围的原话。用 `saveSeasonMonths` 保存当地的季节月份，用 `createSeasonTime` 按当前设置生成季节。已写入草稿或正式记录的季节保留填写时的实际范围。

`listEventTimeline` 返回按年份降序的时间流，每年的 `records` 保存具体日期，`approximate` 保存大概时间。普通跨年范围归入 `crossYear`，跨年季节按填写年份归组，留空或只有原话的记录归入 `undated`。各组内置顶优先，具体日期按当地日期降序，大概时间并列。`listRecentRecords` 继续按置顶及首次记录时间降序返回全部有效正式记录。未完成的修改在点「完成」后才改变分组。[浏览接口](app/src/main/java/com/baconaa/folio/records/RecordBrowsing.kt)

星标与置顶通过 [RecordMarking](app/src/main/java/com/baconaa/folio/records/RecordMarks.kt) 的 `setStarred`、`setPinned` 分别设置，正式详情、浏览卡片、搜索结果及回收站返回 `marks`。标记立即生效，保持内容修改时间和编辑版本；完成或放弃已有草稿保留当前标记。`listStarredRecords` 集中返回已加星的正式记录，沿用最近记录的预览和排序；单独加星不改变顺序。回收站只读，恢复后可继续改标记。Room v10 为旧记录补入默认未加星、未置顶状态。

文字标签通过 [RecordTags](app/src/main/java/com/baconaa/folio/records/RecordTags.kt) 管理：`createDraftTag` 在已有草稿中新建并选中私有标签，`renameDraftTag` 修改其名称，`saveDraftTags` 按顺序保存选中的 `TagId`，`openDraftTags` 重开读取。`listTags` 只提供已经完成的共享标签；同名标签保持不同身份，由用户选择复用。完成时只有选中的新标签转为共享，放弃草稿会删除私有标签，已共享标签在零引用时仍保留。`renameTag` 独立修改共享名称，旧记录和草稿同步显示，完成旧草稿不会写回旧名称。正式详情、回收站详情及浏览卡片通过 `tags` 返回当前名称；标签关系或顺序的修改在完成时更新记录修改时间，共享更名不改变该时间。Room v11 为标签身份及草稿、正式引用分别建表，旧记录默认没有标签。

地点通过 [RecordPlaces](app/src/main/java/com/baconaa/folio/records/RecordPlaces.kt) 管理：`createDraftPlace` 创建并选中草稿私有地点，`selectDraftPlace` 按身份选择或清空，`updateDraftPlace` 编辑私有资料，`updatePlace` 独立更新共享资料。Room v12 新增地点及草稿、正式归属表，旧记录默认没有地点；完成时在同一事务中发布选中地点及归属，未选私有地点随草稿删除。回收站保留正式与草稿两份归属，彻底删除保留已经共享的地点资料。

Room v13 为地点增加可空经纬度，v12 旧地点保留原身份和资料，坐标默认为空。`PlaceInfo.coordinates` 接受有效纬度 [-90, 90] 和经度 [-180, 180]；坐标更新沿用私有／共享资料保存关系。`CurrentLocationSource.locate()` 只返回待确认的当前位置及精度，不执行写入；原生实现最多等待 20 秒，仅接受一分钟内的位置，取消时注销监听。`listPlaces` 在同一读取事务返回记录数和 `recentExperience`：空地点、确定时间、含未定时间或先后不明三种状态。只填日期按事件原时区的整天比较，有时刻则比较实际瞬间；返回原当地表达，不虚构零点或将补写时间当作事件时间。

人物物件通过 [RecordObjects](app/src/main/java/com/baconaa/folio/records/RecordObjects.kt) 管理。`createDraftObject` 创建并选中，`saveDraftObjects` 保存有序多选，`updateDraftObject` 修改本草稿私有资料，`updateObject` 独立保存共享名称、说明和别名。Room v14 新增身份、别名、草稿引用和正式引用表，旧库保留内容、时间、标签、地点及坐标，默认无对象。引用随完成事务发布，失败回滚；记录详情与卡片返回当前 `objects`，旧草稿只回存身份，不覆盖新共享资料。删除恢复保留两份引用，彻底删除保留共享对象。

`search(keyword)` 匹配已完成记录中的正文、清单、自由时间描述、照片和录音说明、标签、地点当前名称、人物物件名称及别名；`queryRecords(keyword, tagIds, starredOnly, placeId, peopleIds, thingIds, mediaTypes)` 返回完整浏览卡片。标签、人物、物件各类别多选满足任一个，各类别及关键词、星标、具体地点同时满足；空集合表示不限该类别，空地点身份表示不限地点，未命中不自动扩大范围。例如“外婆＋搪瓷杯”只返回同时关联两者的记录。两种入口都排除草稿、未完成修改和回收站，不识别原照片中的文字或原录音中的话语。标签、地点、人物与物件按 ID 限定，同名对象不会混在一起；结果沿用置顶及最近记录顺序。在「搜索全部记录 → 内容类型」可多选有照片、有录音、有清单或纯文字，满足其中任一项即可。查询检查整条正式记录，纯文字要求至少一个内容项且每项都是文字。移除内容类型条件会保留关键词及其他筛选。

向 `queryRecords` 传入 `RecordQuery` 可在同一次查询中叠加事件时间。`EventTimeFilter.Within(start..end)` 按事件当地日期筛选，包含首尾两天。`certain`、`possible` 各自按置顶及最近记录排序，列表长度就是对应计数。以 2025 年为例，2025 年秋天明确符合，2025 年冬天因延续到次年 2 月而可能符合。`undatedCount` 给出同样条件下可另行查看的时间未定条数。用 `query.copy(time = EventTimeFilter.Undated)` 单独查看，用 `query.copy(time = null)` 移除时间限制，关键词及其他条件继续生效。[查询接口](app/src/main/java/com/baconaa/folio/records/RecordQuery.kt)

浏览卡片的 `preview` 按原顺序展示最多三个内容项，每项文字及媒体说明截取前 80 个 Unicode 码点；录音返回片段 ID 和时长供试听，清单只读展示前三条事项及全部事项、已完成事项数量。`PreviewContentItem` 与可保存的 `ContentItem` 分开，避免截断预览被回存为完整内容。`remainingItemCount`、`remainingPhotoCount` 和 `remainingAudioCount` 表示未展示的内容项及其中照片、录音项数量，空白文字不占预览，重复使用的媒体仍按各自内容项计数。详情通过同一 `RecordId` 调用 `openRecordContent`，读取完整内容及时间信息。当前列表为全量读取。

删除与恢复使用 [RecordTrash](app/src/main/java/com/baconaa/folio/records/RecordTrash.kt)：`moveToTrash` 将正式记录和编辑草稿一起隐藏，`listTrash` 与 `openTrashRecord` 只展示上次完成的内容并通过 `hasDraft` 提示未完成修改；`restoreRecord` 用于立即撤销或从回收站恢复，两份内容保持独立。重复删除或恢复返回 `false`。详情与回收站页面已接入删除确认、撤销提示和恢复，编辑中的记录可先返回详情再删除。

用户确认后调用 `deletePermanently`，在同一事务中彻底删除回收站记录、编辑草稿和撤销项。完成、放弃、覆盖最近移除及彻底删除都会收集释放的媒体候选，提交后只删除已无任何引用的 Folio 文件；共享引用、回收站、隐藏草稿及可撤销项继续保护原件。`pendingPhotoCount`、`pendingAudioCount` 分别表示仍待清理的照片、录音数量，`null` 表示该类清理暂不可用；文件清理失败不改变已完成的记录删除。`retryPhotoCleanup`、`retryAudioCleanup` 可在重开应用后重试持久队列。清理不扫描尚未绑定的新导入照片或新录音，也不触及系统相册来源。

开发者和 AI 从[开发入口](AGENTS.md)定位本轮需求，按[开发流程](docs/engineering/开发流程.md)领卡、联调和验收。

## 构建和试用调试版

使用 JDK 17 和仓库的 Gradle Wrapper。Android SDK 需安装 `platforms;android-37.0`、`build-tools;36.0.0` 及 `platform-tools`。在 Android Studio 设置 SDK，或令 `ANDROID_HOME` 指向本机 SDK 目录。从仓库根目录在 PowerShell 执行以下命令。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

产物位于 `app/build/outputs/apk/debug/app-debug.apk`。连接 Android 10（API 29）及以上测试设备后，运行 `.\gradlew.bat :app:installDebug` 安装，再从桌面打开 Folio。运行 `.\gradlew.bat :app:connectedDebugAndroidTest` 验证导航、Room 保存与查询、照片、清单、录音与混排、标记、标签、删除恢复、事件时间与浏览及数据库迁移。录音测试会授予测试应用麦克风权限，并在前台录制短片段。macOS 和 Linux 使用 `bash ./gradlew` 和相同任务名。

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
