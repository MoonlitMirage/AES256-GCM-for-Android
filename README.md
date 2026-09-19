# AES256-GCM for Android

A small AES-256-GCM encryption tool for Android. Works with both text and files.

## Features

**Text mode**
- Plaintext + password → Base64 ciphertext, ready to paste into any chat app
- Ciphertext + password → plaintext
- One-tap copy, paste, clear

**File mode**
- Streams files of any size, without loading them into memory, without freezing the UI
- Encrypted files carry a `CYPT` magic header, recognized automatically on decryption
- Export as `.bin` (ciphertext) or `.txt` (plaintext)

## Cryptography

- **Key derivation**: PBKDF2-HMAC-SHA256, 600,000 iterations, random 16-byte salt
- **Cipher**: AES-256-GCM, 128-bit auth tag, random 12-byte nonce
- **Double randomness**: a fresh salt and nonce on every encryption, so the same plaintext never produces the same ciphertext twice
- **Integrity**: GCM's built-in authentication tag — a wrong password or a tampered ciphertext fails loudly
- **Keys never touch disk**: the password and the derived key live only in memory

## Ciphertext Format

**Text mode (before Base64)**

```
[Salt 16B][IV 12B][ciphertext + GCM tag 16B]
```

**File mode**

```
[Magic "CYPT" 4B][Salt 16B][IV 12B][ciphertext stream + GCM tag 16B]
```

## Tech Stack

- Kotlin
- AndroidX AppCompat + View Binding
- Kotlin Coroutines
- Minimum Android 8.0 (API 26)

## Project Layout

```
app/src/main/java/com/example/myapplication/
├── MainActivity.kt          Main screen
├── SettingsActivity.kt      Settings (file-mode toggles)
└── crypto/
    ├── AesGcmCipher.kt      Text encryption
    ├── FileCipher.kt        Streaming file encryption
    └── Base64Util.kt        Pure-Kotlin Base64
```

## Build

```bash
git clone https://github.com/MoonlitMirage/AES256-GCM-for-Android.git
cd AES256-GCM-for-Android
./gradlew assembleDebug
```

Or just open it in Android Studio and hit Run.

## Notes

**Why this exists**

Privacy is a basic right, not a privilege you apply for.

Everything we say in a chat app — private matters with family, thoughts shared with friends, sensitive details at work — is mostly stored in a form the platform can read. The moment you hit send, where that message goes, who has looked at it, how long it is kept, whether it will be pulled up later — you don't really know.

This project turns AES-256-GCM into something an ordinary person can use on Android: type a sentence, get a string of ciphertext, send it. The other side types the same password, and the original text comes back. **Between you and the recipient, no third party gets a hand in, and not a single character is readable to anyone else. What you want to say in private doesn't have to be heard first, or screened first.**

It doesn't solve everything — a weak password can't be saved, a compromised device can't be saved, a password handed over under coercion can't be saved. 
**the words you speak stay unreadable in the middle; the quiet thing you want to say doesn't have to be heard first.**

**Use it lawfully**

Encryption is neutral. Make sure you have the right to handle the content you send, and follow the laws of your jurisdiction. The author is not responsible for how users apply this tool.

## License

MIT

