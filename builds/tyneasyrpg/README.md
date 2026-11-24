# TYN EasyRPG（TYN 外置引擎模块）

面向 [Tyranor-Next (TYN)](https://github.com/Weiss-UltimateSavior/Tyranor-Next) 的
**RPG Maker 2000/2003 外置引擎运行时模块**，基于 EasyRPG Player（TYPEXOC fork，
`tyn-module` 分支）。

## 定位

- 独立 APK 模块，包名 `com.tyranor.runtime.easyrpg`，按
  [TYN 第三方 APK 运行时模块接入指南](https://github.com/Weiss-UltimateSavior/Tyranor-Next/blob/main/docs/%E7%AC%AC%E4%B8%89%E6%96%B9APK%E8%BF%90%E8%A1%8C%E6%97%B6%E6%A8%A1%E5%9D%97%E6%8E%A5%E5%85%A5%E6%8C%87%E5%8D%97.md) 接入。
- launcher 图标直接进入 EasyRPG 上游游戏浏览器，游戏目录可从上游设置中选择；TYN 扫描入库的游戏通过运行时协议交棒启动。
- 游戏画面、侧边栏、设置页和虚拟按键沿用 EasyRPG Android 上游实现；TYN 通过模块启动、翻译接入和侧栏中的作弊面板入口扩展运行时。

## 能力

| 能力 | 状态 |
| --- | --- |
| 2K/2K3 扫描入库（`match: all` + `.ldb` + `RPG_RT.ldb` marker） | ✅ |
| 交棒启动（`FOLDER_PATH` → 引擎命令行） | ✅ |
| 翻译接入（`capabilities: ["translation"]`，`translationRule: "mtool-json"`） | ✅ |
| 译表热重载（ContentObserver + revision 心跳，游戏目录零写入） | ✅ |
| 虚拟按键 | ✅（EasyRPG Player 自带，可经引擎设置关闭；`padEnabled` 声明生效） |
| 单游戏设置（`padEnabled` / `cheatsEnabled` / `encoding`） | ✅ |
| 作弊面板 | ✅（通过 EasyRPG Player 侧边栏打开） |
| 会话诊断日志 | ✅（供 TYN 运行时诊断使用） |

## 翻译接入原理

1. TYN 启动时通过 `com.tyranor.runtime.extra.TRANSLATION_MAP_URI` 授予只读
   mtool JSON map URI。
2. 模块把 map 转写成 EasyRPG 语言目录格式的 PO 文件（`rpg_rt.ldb.po`），
   存放于**模块私有缓存**（游戏目录零写入）。
3. 引擎以 `--language-path <缓存目录> --language tyn` 启动，PO 原生加载。
4. Player fork（`tyn-module` 分支）的两处配套改动：
   - `Dictionary::TranslateString` 空 context 回退——无 msgctxt 的平面 map
     可命中全部引擎查找；
   - `requestTranslationReload` JNI 入口 + 主循环轮询——译表更新时
     在游戏线程重跑 `SelectLanguage`（重读目录、重写数据库、刷新画面）。
5. 译表更新：重新读 URI → 原子重写 PO → 触发 native 重载。

## 构建

```bash
# 1. 用 EasyRPG buildscripts 构建 android 依赖工具链（见 CI 脚本）
# 2. 构建 APK（Player 源码树与模块工程同级时使用默认值）
./gradlew assembleDebug
# 指定路径
./gradlew assembleDebug -PplayerSrc=../Player -PtoolchainDirs=/path/to/buildscripts/android
```

`toolchainDirs` 指向 buildscripts `android/` 工作区根（CMake 会按 ABI 追加
`<abi>-toolchain`），与 EasyRPG Player 官方安卓构建一致。

签名沿用 TYN RGSS 模块约定：CI 环境变量 `ANYPLAY_KEYSTORE_FILE`、
`ANYPLAY_STORE_PASSWORD`、`ANYPLAY_KEY_ALIAS`、`ANYPLAY_KEY_PASSWORD`；
未配置时回退 debug key（保证可安装、可覆盖升级测试）。

## 模块清单声明

`app/src/main/res/raw/tyranor_runtime.json`：

```json
{
  "protocol": 1,
  "label": "TYN EasyRPG",
  "capabilities": ["translation"],
  "translationRule": "mtool-json",
  "scan": { "match": "all", "extensions": [".ldb"], "markers": ["RPG_RT.ldb"] },
  "settings": { "version": 1, "items": [ ... ] }
}
```
