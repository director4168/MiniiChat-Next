<div align="center">

# MiniiChat Next  

**一个开源的安卓LLM聊天客户端**  

[![License: AGPL-3.0](https://img.shields.io/badge/License-AGPL--3.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.21-7F52FF.svg)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-2024.12-4285F4.svg)](https://developer.android.com/jetpack/compose)
[![minSdk](https://img.shields.io/badge/minSdk-26-7B61FF.svg)](https://developer.android.com)
[![targetSdk](https://img.shields.io/badge/targetSdk-35-7B61FF.svg)](https://developer.android.com)

</div>

---

## 简介  

本应用/项目(MiniiChat Next)由*director_Carter*基于*MiniiChat*项目(MIT)开发。MiniiChat Next是一款开源的安卓LLM客户端，此衍生版本在原有基础上添加了更多功能*（如MCP服务调用支持、工具调用、文件操作等）  
**如有bug反馈或功能建议，可提交*[Github Issus](https://github.com/director4168/MiniiChat-Next/issues)*或通过邮箱联系**

[**第三方组件清单**](AGPL_NOTICE.md)

## 构建  

在开始之前，请确保您的环境：

| 项目 | **推荐或最低版本** |
| --- | --- |
| Android Studio | 2025.1.3 |
| JDK | 17+ |
| Gradle | 8.13 |
| AGP | 8.13.0 |
| Kotlin | 2.1.0 |
| Android SDK | Platform 36 + Build-Tools 36.x |
| NDK | r30 |

**1. 克隆仓库**  
```bash
git clone https://github.com/director4168/MiniiChat-Next.git
```

**2. 构建**  
```bash
# 构建Debug包（用于测试）
./gradlew assembleDebug

# 构建Release包（用于分发）
./gradlew assembleRelease
```

## 关于我  

一名普普通通的用户  

### 联系方式  

- 哔哩哔哩:
[director_Carter](https://b23.tv/tk7CcWA)  

- MT论坛:
[director_mark](https://bbs.binmt.cc/home.php?mod=space&uid=134138&do=profile&mobile=2)  

- QQ:  
2705722903  
3623293903  

- 邮箱:  
director4168@163.com  
2705722903@qq.com  
3623293903@qq.com  

---

## 部分修改内容  

MiniiChat Next主要修改了以下方面：  
- **许可证**: MIT -> **AGPL-3.0**  
- **构建**: 修改了构建输出路径、签名与包名  
- **候选词缓存**: 按 `页` 持久化  
- **辅助模型**: OCR/标题/压缩/候选，需要在设置中配置*辅助模型*  
- **Skill**: 支持了使用SKILL技能书  
- **API**: 支持了Anthropic克劳德格式的API接入  
- **Response API**: 支持了启用Response API  
- **继续说**: AI回复追加**继续说**  
- **自动标题**: 在完成第一轮对话后，使用所选的标题辅助模型，自动命名当前对话  
- **沉浸模式**: 双击空白处沉浸、渐隐（可以用来看背景图片）  
- **AI气泡**: AI回复的内容也有气泡  
- **设置**: 辅助模型、助手SKILL等
- **MCP/工具**: 内置文件工具、外部MCP工具调用
- **工作区**: 支持创建多个独立工作区，每个助手可绑定其中一个  
- **语言切换**: 优化了语言切换逻辑，切换语言后软件将自动重启与应用语言，原版本需要手动将软件后台划掉才可以应用新语言  
- ……  

---

## 打赏

您可选择通过 微信/支付宝 向我打赏，这将有助于我对该项目的开发
<img src="https://director4168.page.gd/DSM.png" alt="打赏收款码">