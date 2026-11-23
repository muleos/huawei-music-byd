# 华为音乐 · 比亚迪车机版

以开源播放器 [Sonance](https://github.com/gxslcl/Sonance) 的界面与本地播放能力为基础，把在线数据源替换为华为音乐，面向比亚迪 DiLink 横屏车机使用。

- 应用名：**华为音乐**
- 包名：`com.huawei.music.byd`
- 当前版本：**2.6.6**（versionCode 24）
- 界面：保留 Sonance 原版布局，未重做 UI

## 已实现

- 华为账号 OAuth 登录（网页 / 扫码），PKCE S256
- 华为音乐在线搜索、歌单、收藏、播放历史、每日推荐、猜你喜欢
- 自建歌单 / 收藏歌单大分页读取（多路补查 + 部分成功回退）
- 横屏默认进入在线音乐；在线页右上角关闭回本地
- 横屏播放页底部导航栏安全区，封面不再被车机任务栏遮挡
- 比亚迪仪表盘“当前播放”上报：
  - `AudioManager.setPlayMediaInfo/setPlayState/setPlayProgress`（BYD 媒体中心扩展）
  - `BYDAutoInstrumentDevice.sendMusicInfo/sendMusicState/sendMusicPlaybackProgress`
  - `BYDAutoAudioDevice.sendSingerName/sendAudioTotalTime/sendAudioPlayingTime`
  - 歌名按 UTF-16LE、最多 96 字节
  - 封面缩略图写入标准 `MediaMetadata.artworkData`，不依赖 FileProvider URI 授权
- 注册 `MUSIC_PLAYER` / `APP_MUSIC`，便于车机媒体中心识别

## 构建

环境：JDK 17、Android SDK 35、Gradle。

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
./gradlew assembleDebug
```

产物：

```
app/build/outputs/apk/debug/app-debug.apk
```

签名默认使用 Android Debug 证书；正式分发请替换为自己的 release keystore，不要把 keystore 和口令提交到仓库。

## 比亚迪仪表盘说明

比亚迪仪表盘私有协议只提供 **歌名 / 歌手 / 专辑 / 时长**，没有标准封面字段。部分 DiLink 车型的 VHAL 还会对 `BYDAUTO_INSTRUMENT_*` 做平台签名 ACL，普通 debug 签名应用可能被 `nativeSetInt permission deny` 拒绝。

本项目的处理方式是：

1. 双链路尽力上报歌名等信息；失败静默降级，不影响播放。
2. 同时把封面压缩到 720px JPEG 写入 `MediaMetadata.artworkData`，供支持标准媒体会话封面的车机读取。
3. 动态探测车型是否提供 `sendMusicAlbumPic` 一类 byte[] 厂商方法，有则顺带发送。

如果实车只显示歌名、不显示封面，通常是车型仪表协议无封面字段或 ACL 限制；需要比亚迪官方合作 / 平台签名才能进一步打通。

## 华为账号与接口

华为登录与接口调用使用华为音乐公开客户端参数，仅用于个人车机上的账号授权访问。请遵守华为音乐服务条款，不要将账号凭据、Cookie、token 提交到仓库。

## License

上游 Sonance 的许可证见 [LICENSE](LICENSE)。
