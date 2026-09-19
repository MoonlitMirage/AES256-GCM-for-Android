# AES256-GCM for Android

一个 Android 端 AES-256-GCM 加解密小工具，支持文本和文件两种模式。

## 功能

**文本模式**
- 明文 + 密码 → Base64 密文，可直接复制粘贴到 QQ / 微信
- 密文 + 密码 → 明文
- 一键复制、粘贴、清空

**文件模式**
- 任意大小的文件流式加解密，不整个读进内存，不卡 UI
- 加密后文件带 `CYPT` 魔数头，解密时自动识别
- 支持 `.bin`（密文）与 `.txt`（明文）导出

## 加密方案

- **密钥派生**：PBKDF2-HMAC-SHA256，600,000 次迭代，随机 16 字节 Salt
- **加密算法**：AES-256-GCM，128 位认证标签，随机 12 字节 Nonce
- **双随机**：每次加密都重新生成 Salt 和 Nonce，同一明文多次加密结果不同
- **完整性**：GCM 自带认证标签，密钥错误或密文被篡改会明确失败
- **密钥不落盘**：密码与派生密钥只存在于内存

## 密文格式

**文本模式（Base64 编码前）**

```
[Salt 16B][IV 12B][密文 + GCM Tag 16B]
```

**文件模式**

```
[Magic "CYPT" 4B][Salt 16B][IV 12B][密文流 + GCM Tag 16B]
```

## 技术栈

- Kotlin
- AndroidX AppCompat + View Binding
- Kotlin Coroutines
- 最低支持 Android 8.0（API 26）

## 项目结构

```
app/src/main/java/com/example/myapplication/
├── MainActivity.kt          主界面
├── SettingsActivity.kt      设置页（文件模式开关）
└── crypto/
    ├── AesGcmCipher.kt      文本加解密
    ├── FileCipher.kt        文件流式加解密
    └── Base64Util.kt        纯 Kotlin Base64 实现
```

## 编译运行

```bash
git clone https://github.com/MoonlitMirage/AES256-GCM-for-Android.git
cd AES256-GCM-for-Android
./gradlew assembleDebug
```

或用 Android Studio 直接打开运行。

## 说明
隐私是一项基本权利，不是需要向谁申请的特权。

我们每天在聊天软件里说的话——和家人的私事、和朋友的想法、工作上的敏感信息——大多是以平台可读的形式存在的。你按下发送键的那一刻，那句话去了哪里、被谁看过、存了多久、以后会不会被翻出来，你并不真的知道。

这个项目把 AES-256-GCM 做成了一个普通人也能用的 Android 工具：输入一句话，得到一串密文，发出去；对方输入同样的密码，还原出原文。中间那串字符，只有你和收信人能读懂。
它让一件事成为可能：你说的话，中间没人读得懂。

有些话，本来就只该说给该听的人听。
请合法使用

加密工具本身是中性的。请确保你对所传内容拥有处置权，并遵守你所处地区的法律法规。作者不对使用者的行为负责。

## License

MIT

