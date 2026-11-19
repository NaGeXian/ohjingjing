# 哦鲸鲸

一个自己填 API Key 的 DeepSeek 手机客户端。没有服务器、不收钱、不夹带任何账号体系——装上、填你自己的 key、开始聊。

> 非官方应用，与 DeepSeek 官方无关。用的是 DeepSeek 开放平台的接口，费用由你自己在平台账户里结算。

## 它是什么

一个 Android 壳子。整个界面是一个 HTML 文件，套在系统 WebView 里，通过 `api.deepseek.com` 直接和你填的 key 通信。

- **没有服务器**：请求从你手机直接发到 DeepSeek，中间没有任何中转
- **没有账号**：不注册、不登录、不上报
- **key 只存在本机**：存在 WebView 的本地存储里，卸载即消失
- **不盈利**：没有付费墙、没有广告、没有埋点

## 功能

| | |
|---|---|
| 对话 | 流式输出、深度思考（可折叠）、思考强度三档 |
| 联网 | 智能搜索、阅读链接 |
| 附件 | 拍照、相册、文件（PDF / Word / PPT / 图片） |
| 会话 | 搜索、置顶、重命名、批量删除、按日期分组 |
| 阅读 | 回答朗读（系统 TTS，离线）、自动朗读 |
| 提示词 | 设置里可改系统提示词，默认值让口气更接近网页端；清空则完全不发 |
| 外观 | 跟随系统 / 浅色 / 深色、字号三档 |
| 数据 | 导入 DeepSeek 网页端导出的 `deepseek_data-*.zip`，继续之前的话题 |

## 安装

1. 下载 [apk/ohjingjing.apk](apk/ohjingjing.apk)
2. 传到手机上安装（会提示「未知来源」，允许即可）
3. 首次打开会让你填 API Key

### 去哪拿 API Key

打开 [platform.deepseek.com](https://platform.deepseek.com) 注册，在 **API keys** 里新建一个，复制粘贴进来。新账号通常有赠送额度，用完之后要自己充值。

这个 key 只填在你自己手机上，不会经过任何第三方。

## 自己编译

需要 JDK 17 和 Android SDK（build-tools 34 / platform android-34）。

```powershell
$env:JAVA_HOME = "C:\path\to\jdk-17"
$env:ANDROID_HOME = "C:\path\to\android-sdk"
powershell -File android\build.ps1
```

不用 Gradle，脚本直接走 `aapt2 → javac → d8 → zipalign → apksigner`。第一次跑会在 `android/` 下生成一个自签名 keystore。

改界面只需要动 `src/index.html` 这一个文件，它会被原样打进 APK 的 `assets/`。

## 一些实现上的取舍

- **参数基本不传**：`temperature`、`top_p`、各种 penalty 在思考模式下本来就不生效，开了联网工具或标题生成之后服务端还会直接忽略，所以干脆不传，免得让人误以为在调节什么。
- **会话标题交给服务端生成**（`title.enabled`），和网页端行为一致。
- **历史存在 IndexedDB**：网页端导出的完整历史有几十 MB，localStorage 那 5MB 根本装不下。启动只读元数据，点开某条才拉正文。
- **系统提示词放在设置里**：默认那句只做一件事——让它说话像网页端（先给结论、少堆列表、不硬凑小标题）。每轮随请求发一次，代价大约一百来个 token；清空就退化成裸模型。自己改的时候别写太长，长了会挤占上下文，也会让回答变得端着。
- **截断会说明白**：流结束时读 `finish_reason`，遇到 `length` / `max_tokens`，就在回答下面标一条提示并给「继续」按钮，不把"说到一半"当成"说完了"。
- **每一轮都会带 `latest_reminder`**，把日期和客户端类型告诉模型，这样它才知道"今天"是几号。

## 已知限制

- 超长的历史会话（比如上千轮）继续对话可能超出上下文长度限制
- 导入时附件原图不跟着进来，只导入文字
- 朗读音色取决于手机自带的语音引擎，不同机型不一样

## 许可

代码以 [MIT](LICENSE) 许可发布——随便用、随便改，保留版权声明即可。图标（android/res/mipmap-*）是自己画的，请勿用于商业用途。
