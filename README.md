# SwipeClean 滑卡清理

安卓相册清理 App：把照片和视频变成卡片，左滑删除、右滑跳过、上滑/双击收藏。全部本地处理，无广告、无上传、零数据收集。

![](https://img.shields.io/badge/platform-Android%2011%2B-green) ![](https://img.shields.io/badge/Kotlin-2.1-blue) ![](https://img.shields.io/badge/Jetpack%20Compose-Material3-orange)

## 功能

| 模块 | 说明 |
|---|---|
| 整理滑卡（照片+视频轮） | 卡片堆叠，四向手势，每轮 30 张；整理完成经「确认删除」进系统回收站（30 天可恢复），可一键「不删了全部还原」 |
| 视频滑卡 | 抖音式全屏播放：自动循环、单击暂停、双击收藏、长按 2x、进度条 seek、默认静音；每轮 15 个，支持大文件优先 |
| 混合轮 | 照片+视频混排（20+10） |
| 相似清理 | ThumbHash 感知哈希 + 时间窗预筛 + 并查集聚类，全本地 |
| 足迹 | EXIF GPS + 省份边界（cn-atlas 数据）聚合 |
| 收藏 | 对齐系统 IS_FAVORITE，相册 App 可见 |

## 构建要求

- JDK 17、Android SDK 35
- `./gradlew assembleDebug`（本地）或 GitHub Actions 自动构建（见下）

## 签名说明

仓库 CI 只产出 **unsigned** 包；签名钥不入库。自签名：

```sh
zipalign -f -p 4 app-release-unsigned.apk aligned.apk
apksigner sign --ks your.keystore --out signed.apk aligned.apk
```

## 技术要点

- **手势仲裁**：单个 `awaitEachGesture` 内实现四向拖拽 × 单击 × 双击 × 长按共存（四向滑卡与抖音式播放手势天然冲突，这是本项目最核心的一块）
- **播放器双实例池**：Media3 ExoPlayer A 播放 + B 预载，起播 ≤400ms；FLY_OUT 第一帧即 pause
- **删除链路**：`MediaStore.createTrashRequest` 对齐系统「最近删除」，无自建回收站
- **相似算法**：ThumbHash（DCT 低频）+ 3 分钟时间窗预筛 + 并查集，对标 iOS Vision FeaturePrint 的本地替代管线
- **权限兼容**：READ_MEDIA_*（13+）/ READ_EXTERNAL_STORAGE（≤12）版本化分支

## 数据与隐私

- 无网络权限：App 不声明 INTERNET，物理上无法上传
- 无第三方 SDK、无分析、无崩溃上报
- 删除 = 系统回收站，收藏 = 系统字段

## License

MIT
