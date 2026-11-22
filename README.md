# 星铁打卡小助手

一款《崩坏：星穹铁道》第三方辅助 Android 应用，提供打卡提醒、战绩/面板查询、抽卡保底分析、签到与成就图鉴等辅助功能。

> **⚠️ 非官方声明**
> 本项目为个人学习与兴趣项目，**与米哈游 / 上海米哈游天命科技有限公司、HoYoverse 无任何关联**。
> 游戏名称、角色名、图标、立绘、光锥、遗器等相关素材的著作权归原权利人所有。
> 本项目仅用于技术学习交流，请勿用于商业用途；因使用本应用产生的任何后果由使用者自行承担。

## 功能

- 打卡 / 体力 / 派遣等多时段提醒，到点弹窗提醒
- Cookie 登录后查询战绩、开拓力、派遣、签到等数据
- 角色 / 光锥 / 遗器面板与白值查看
- 抽卡记录分析：卡池分类、小保底歪不歪判定、五星明细、保底水位与攒抽规划
- 成就 / 图鉴收集度统计
- 深浅色主题适配

## 技术栈

- Kotlin + Jetpack Compose + Material 3
- UI 组件库：[Miuix KMP](https://github.com/compose-miuix-ui/miuix)
- 网络：OkHttp / Kotlinx Coroutines
- 图片：自研内存 + 磁盘 LRU 缓存，多源在线兜底
- 最低支持 Android 8.0（API 26），编译 SDK 36

## 构建

环境要求：JDK 17、Android SDK（Build Tools 36.0.0）。

## 资源与素材说明

- 仓库**不随源码分发**角色头像（`assets/avatar/`）与立绘（`assets/portrait/`）；其余光锥、遗器、技能、奖励、元素与命途图标随源码入库。
- 头像 / 立绘在应用运行时通过在线源加载并写入本地磁盘缓存，主要数据源：
  - [Mar-7th/StarRailRes](https://github.com/Mar-7th/StarRailRes)
  - [vizualabstract/StarRailStaticAPI](https://github.com/vizualabstract/StarRailStaticAPI)
- `app/src/main/assets/` 下的 `enka/*.json`、`miao_*.json` 等为角色 / 光锥 / 遗器映射与规则数据文件，随源码提供。
- 若上游在线源不可用，对应图片将回退为占位样式。

## 隐私说明

- 登录凭证（Cookie）仅保存在本机应用私有目录，用于向官方接口请求数据，**不上传至开发者搭建的服务器**。
- 应用不收集与功能无关的个人信息；备份 / 导出数据由你主动触发并自行保管。

## 第三方开源依赖

本项目使用了以下开源组件（均为 Apache-2.0 等宽松许可）：

- AndroidX / Jetpack Compose（Apache-2.0）
- Material Components for Android（Apache-2.0）
- Miuix KMP（Apache-2.0）
- OkHttp（Apache-2.0）
- Kotlinx Coroutines / Kotlin Standard Library（Apache-2.0）
- ZXing（Apache-2.0）
- uCrop（Apache-2.0）
- Kyant0 Backdrop / Shapes

详见应用内「关于 → 引用」页面；第三方素材、数据与组件的逐条归属声明见 [NOTICE](./NOTICE)。

## 免责声明

本应用按"现状"提供，不提供任何明示或默示的担保。接口可能随官方版本更新而变动，导致部分功能不可用。
使用本应用时请遵守游戏用户协议与相关法律法规，因账号操作、数据异常等造成的损失，本项目不承担责任。

## 开源协议

本项目源码采用 [Apache License 2.0](./LICENSE) 协议发布。
