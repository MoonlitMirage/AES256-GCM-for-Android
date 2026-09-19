package com.example.myapplication

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.crypto.AesGcmCipher
import com.example.myapplication.crypto.FileCipher
import com.example.myapplication.databinding.ActivityMainBinding
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * 主界面：
 * - 加密：明文输入框内容 -> 密文框，并自动复制到剪贴板
 * - 解密：密文框内容 -> 明文输入框
 * - 辅助：粘贴密文到密文框、复制密文、清空输入框
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** 加解密进行中标志：防止重复点击同时触发多个 PBKDF2 计算 */
    private var processing = false

    /** 打开文件的目标输入框（明文框或密文框，文本模式载入用） */
    private var pendingLoadTarget: TextInputEditText? = null

    /** 打开文件角色：0=明文框载入，1=密文框载入，2=明文源 txt，3=密文源 bin */
    private var pendingOpenRole = 0

    /** 单文件加载上限：超出则拒绝，避免超大文本 setText 卡死主线程（EditText 布局瓶颈） */
    private val maxFileBytes = 1024L * 1024L

    /** 文件加载中的提示条（大文件读取时显示） */
    private var loadingSnackbar: Snackbar? = null

    private val fileOpenLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val role = pendingOpenRole
            pendingOpenRole = 0
            if (uri == null) return@registerForActivityResult
            when (role) {
                0 -> loadFileInto(uri, binding.inputEdit)
                1 -> loadFileInto(uri, binding.cipherEdit)
                2 -> {
                    pendingPlainFile = uri
                    showMsg(R.string.msg_plain_file_selected)
                }
                else -> {
                    pendingCipherFile = uri
                    showMsg(R.string.msg_cipher_file_selected)
                }
            }
        }

    /** 文件模式：明文源（txt）与密文源（bin），打开文件后记录，由加密/解密按钮执行 */
    private var pendingPlainFile: android.net.Uri? = null
    private var pendingCipherFile: android.net.Uri? = null

    /** 文件输出模式：0=文件→bin 加密，1=bin→文件 解密，2=文本→bin 加密，3=文本密文→txt 解密 */
    private var pendingFileMode = 0
    private var pendingFileInput: android.net.Uri? = null
    private var pendingExportContent: String? = null

    /** 加密输出（bin）：mime 为 octet-stream，保存对话框按二进制文件处理 */
    private val binOutputLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            val mode = pendingFileMode
            val input = pendingFileInput
            val content = pendingExportContent
            pendingFileMode = 0
            pendingFileInput = null
            pendingExportContent = null
            if (uri == null) return@registerForActivityResult
            when (mode) {
                0 -> processFileStream(input ?: return@registerForActivityResult, uri, encrypt = true)
                else -> processTextToBin(content ?: return@registerForActivityResult, uri)
            }
        }

    /** 解密输出（txt）：mime 为 text/plain，保存对话框按文本文件处理，明文不会被存成 bin */
    private val txtOutputLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            val mode = pendingFileMode
            val input = pendingFileInput
            val content = pendingExportContent
            pendingFileMode = 0
            pendingFileInput = null
            pendingExportContent = null
            if (uri == null) return@registerForActivityResult
            when (mode) {
                1 -> processFileStream(input ?: return@registerForActivityResult, uri, encrypt = false)
                else -> processTextToFile(content ?: return@registerForActivityResult, uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyStatusBarInset()

        binding.btnEncrypt.setOnClickListener { encryptInput() }
        binding.btnDecrypt.setOnClickListener { decryptInput() }
        binding.btnCopyPlain.setOnClickListener { copyPlain() }
        binding.btnPastePlain.setOnClickListener { pasteIntoPlain() }
        binding.btnClearPlain.setOnClickListener { binding.inputEdit.text?.clear() }
        binding.btnCopyCipher.setOnClickListener { copyCipher() }
        binding.btnPasteCipher.setOnClickListener { pasteIntoCipher() }
        binding.btnClearCipher.setOnClickListener { binding.cipherEdit.text?.clear() }
        binding.btnOpenFile.setOnClickListener { openCipherFile() }
        binding.btnOpenFilePlain.setOnClickListener { openPlainFile() }

        // 按文件模式开关设置界面可见性（onResume 也会刷新，设置页返回即生效）
        applyModeVisibility()

        // 工具栏菜单：设置入口
        binding.toolbar.inflateMenu(R.menu.menu_main)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Android 15+ 强制 edge-to-edge：给根布局顶部加状态栏高度的 padding，
     * 使 Toolbar 等内容显示在状态栏下方（状态栏区域为背景色）。
     */
    private fun applyStatusBarInset() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, 0)
            WindowInsetsCompat.CONSUMED
        }
    }

    override fun onResume() {
        super.onResume()
        // 从设置页返回时按开关刷新文本框与按钮可见性
        applyModeVisibility()
    }

    /**
     * 按文件模式开关设置界面状态：
     * - 明文从 txt 加载（开）：明文框变灰禁用，复制/粘贴/清空禁用，仅显示“打开 txt 文件”
     * - 密文输出为 bin（开）：密文框变灰禁用，复制/粘贴/清空禁用，仅显示“打开 bin 文件”
     */
    private fun applyModeVisibility() {
        val plainTxt = plainFromTxt()
        val cipherBin = cipherToBin()

        // 明文侧
        binding.inputLayout.isEnabled = !plainTxt
        binding.inputEdit.isEnabled = !plainTxt
        binding.btnCopyPlain.isEnabled = !plainTxt
        binding.btnPastePlain.isEnabled = !plainTxt
        binding.btnClearPlain.isEnabled = !plainTxt
        binding.btnOpenFilePlain.visibility = if (plainTxt) View.VISIBLE else View.GONE

        // 密文侧
        binding.cipherLayout.isEnabled = !cipherBin
        binding.cipherEdit.isEnabled = !cipherBin
        binding.btnCopyCipher.isEnabled = !cipherBin
        binding.btnPasteCipher.isEnabled = !cipherBin
        binding.btnClearCipher.isEnabled = !cipherBin
        binding.btnOpenFile.visibility = if (cipherBin) View.VISIBLE else View.GONE
    }

    /**
     * 加密：按文件模式开关选择路径
     * - 明文源：明文框（关）或 txt 文件（开）
     * - 密文去向：密文框 Base64（关）或 bin 文件（开）
     */
    private fun encryptInput() {
        if (processing) return
        val key = currentKey() ?: return
        if (plainFromTxt()) {
            val file = pendingPlainFile
            if (file == null) {
                showMsg(R.string.msg_need_plain_file)
                return
            }
            if (cipherToBin()) {
                // txt -> bin：流式加密，选输出位置（bin 类型）
                pendingFileMode = 0
                pendingFileInput = file
                binOutputLauncher.launch(getString(R.string.encrypt_file_name, stripExtension(displayName(file))))
            } else {
                // txt -> Base64 密文框
                encryptFileToBase64(file, key)
            }
        } else {
            val plain = binding.inputEdit.text?.toString().orEmpty().trim()
            if (plain.isEmpty()) {
                showMsg(R.string.msg_need_content)
                return
            }
            if (cipherToBin()) {
                // 明文框文本 -> bin
                pendingFileMode = 2
                pendingExportContent = plain
                binOutputLauncher.launch(getString(R.string.encrypt_text_file_name))
            } else {
                // 明文框 -> Base64 密文框（PBKDF2 后台执行）
                setProcessing(true)
                lifecycleScope.launch {
                    try {
                        val cipher = withContext(Dispatchers.Default) { AesGcmCipher.encrypt(plain, key) }
                        setProcessing(false)
                        binding.cipherEdit.setText(cipher)
                        copyToClipboard(cipher)
                        showMsg(R.string.msg_encrypted_copied)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        setProcessing(false)
                        showMsg(R.string.msg_encrypt_fail)
                    }
                }
            }
        }
    }

    /**
     * 解密：按文件模式开关选择路径
     * - 密文源：密文框（关）或 bin 文件（开）
     * - 明文去向：明文框（关）或 txt 文件（开）
     */
    private fun decryptInput() {
        if (processing) return
        val key = currentKey() ?: return
        if (cipherToBin()) {
            val file = pendingCipherFile
            if (file == null) {
                showMsg(R.string.msg_need_cipher_file)
                return
            }
            if (plainFromTxt()) {
                // bin -> txt：流式解密，选输出位置（txt 类型）
                pendingFileMode = 1
                pendingFileInput = file
                txtOutputLauncher.launch(getString(R.string.decrypt_file_name, stripExtension(displayName(file))))
            } else {
                // bin -> 明文框（流式解到内存）
                decryptFileToPlainText(file, key)
            }
        } else {
            val cipher = binding.cipherEdit.text?.toString().orEmpty().trim()
            if (cipher.isEmpty()) {
                showMsg(R.string.msg_no_cipher)
                return
            }
            if (plainFromTxt()) {
                // 密文框 Base64 -> txt 文件
                pendingFileMode = 3
                pendingExportContent = cipher
                txtOutputLauncher.launch(getString(R.string.decrypt_text_file_name))
            } else {
                // 密文框 -> 明文框（PBKDF2 后台执行）
                setProcessing(true)
                lifecycleScope.launch {
                    try {
                        val plain = withContext(Dispatchers.Default) { AesGcmCipher.decrypt(cipher, key) }
                        setProcessing(false)
                        binding.inputEdit.setText(plain)
                        showMsg(R.string.msg_decrypted)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        setProcessing(false)
                        showMsg(e.message ?: getString(R.string.msg_decrypt_fail))
                    }
                }
            }
        }
    }

    private fun currentKey(): String? {
        val key = binding.keyEdit.text?.toString().orEmpty()
        if (key.isEmpty()) {
            showMsg(R.string.msg_need_key)
            return null
        }
        return key
    }

    /** 加解密期间禁用操作按钮，防止重复点击 */
    private fun setProcessing(value: Boolean) {
        processing = value
        binding.btnEncrypt.isEnabled = !value
        binding.btnDecrypt.isEnabled = !value
    }

    /** 密文卡片“打开文件”：bin 模式开启时选择密文源（bin），否则载入密文框 */
    private fun openCipherFile() {
        if (cipherToBin()) {
            pendingOpenRole = 3
            fileOpenLauncher.launch(arrayOf("*/*"))
        } else {
            pendingOpenRole = 1
            fileOpenLauncher.launch(arrayOf("text/plain"))
        }
    }

    /** 明文卡片“打开文件”：txt 模式开启时选择明文源（txt），否则载入明文框 */
    private fun openPlainFile() {
        if (plainFromTxt()) {
            pendingOpenRole = 2
            fileOpenLauncher.launch(arrayOf("text/plain"))
        } else {
            pendingOpenRole = 0
            fileOpenLauncher.launch(arrayOf("text/plain"))
        }
    }

    private fun loadFileInto(uri: android.net.Uri, target: TextInputEditText) {
        // 先检查文件大小：超大文件直接拒绝，避免 setText 大文本卡死主线程
        val size = runCatching {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull()
        if (size != null && size > maxFileBytes) {
            showMsg(getString(R.string.msg_file_too_large, size / 1024, maxFileBytes / 1024))
            return
        }
        // 大文件读取在后台线程，先显示加载提示，避免界面看起来没反应
        loadingSnackbar = Snackbar.make(binding.root, R.string.msg_file_loading, Snackbar.LENGTH_INDEFINITE)
        loadingSnackbar?.show()
        lifecycleScope.launch {
            val text = runCatching {
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { input ->
                        input.bufferedReader(Charsets.UTF_8).readText()
                    }
                }
            }.getOrNull()
            if (text.isNullOrEmpty()) {
                loadingSnackbar?.dismiss()
                loadingSnackbar = null
                showMsg(R.string.msg_file_read_fail)
            } else {
                // 分块渐进填充：避免一次性 setText 大文本卡死主线程（加载提示全程可见）
                fillTextGradually(target, text)
            }
        }
    }

    /**
     * 把大文本分块（4KB/块）逐块 append 到输入框，每块之间让出主线程允许界面绘制。
     * 填充完成才关闭加载提示并显示成功提示。
     */
    private fun fillTextGradually(target: TextInputEditText, text: String) {
        target.setText("")
        val chunkSize = 16 * 1024
        var index = 0
        fun fillNext() {
            if (index < text.length) {
                val end = minOf(index + chunkSize, text.length)
                target.append(text.substring(index, end))
                index = end
                target.post { fillNext() }
            } else {
                loadingSnackbar?.dismiss()
                loadingSnackbar = null
                showMsg(R.string.msg_file_loaded)
            }
        }
        fillNext()
    }

    private fun prefs() = getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)

    /** 文件模式开关：明文从 txt 加载（明文框隐藏） */
    private fun plainFromTxt() = prefs().getBoolean(SettingsActivity.KEY_PLAIN_FROM_TXT, false)

    /** 文件模式开关：密文输出为 bin（密文框隐藏） */
    private fun cipherToBin() = prefs().getBoolean(SettingsActivity.KEY_CIPHER_TO_BIN, false)

    /** 去掉文件扩展名（用于建议输出文件名） */
    private fun stripExtension(name: String): String {
        val idx = name.lastIndexOf('.')
        return if (idx > 0) name.substring(0, idx) else name
    }

    /** 后台流式加解密文件（文件 -> 文件），过程中显示不定进度提示 */
    private fun processFileStream(input: android.net.Uri, output: android.net.Uri, encrypt: Boolean) {
        val key = currentKey() ?: return
        loadingSnackbar = Snackbar.make(
            binding.root,
            if (encrypt) R.string.msg_file_encrypting else R.string.msg_file_decrypting,
            Snackbar.LENGTH_INDEFINITE
        )
        loadingSnackbar?.show()
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val ins = contentResolver.openInputStream(input)
                    val outs = contentResolver.openOutputStream(output)
                    if (ins == null || outs == null) {
                        throw IOException("无法打开文件")
                    }
                    try {
                        if (encrypt) FileCipher.encryptFile(ins, outs, key)
                        else FileCipher.decryptFile(ins, outs, key)
                    } finally {
                        ins.close()
                        outs.close()
                    }
                }
            }
            loadingSnackbar?.dismiss()
            loadingSnackbar = null
            result.onSuccess {
                showMsg(if (encrypt) R.string.msg_file_encrypted else R.string.msg_file_decrypted)
            }.onFailure { e ->
                showMsg(e.message ?: getString(R.string.msg_encrypt_fail))
            }
        }
    }

    /** 明文框文本 -> bin 文件（文本流式加密） */
    private fun processTextToBin(text: String, output: android.net.Uri) {
        val key = currentKey() ?: return
        loadingSnackbar = Snackbar.make(binding.root, R.string.msg_file_encrypting, Snackbar.LENGTH_INDEFINITE)
        loadingSnackbar?.show()
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(output)?.use { out ->
                        FileCipher.encryptFile(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)), out, key)
                    } ?: throw IOException("无法打开文件")
                }
            }
            loadingSnackbar?.dismiss()
            loadingSnackbar = null
            result.onSuccess { showMsg(R.string.msg_file_encrypted) }
                .onFailure { e -> showMsg(e.message ?: getString(R.string.msg_encrypt_fail)) }
        }
    }

    /** 密文框 Base64 文本 -> txt 文件（解密后写出） */
    private fun processTextToFile(cipherText: String, output: android.net.Uri) {
        val key = currentKey() ?: return
        loadingSnackbar = Snackbar.make(binding.root, R.string.msg_file_decrypting, Snackbar.LENGTH_INDEFINITE)
        loadingSnackbar?.show()
        lifecycleScope.launch {
            val result = runCatching {
                val plain = withContext(Dispatchers.Default) { AesGcmCipher.decrypt(cipherText, key) }
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(output)?.use { out ->
                        out.write(plain.toByteArray(Charsets.UTF_8))
                    } ?: throw IOException("无法打开文件")
                }
            }
            loadingSnackbar?.dismiss()
            loadingSnackbar = null
            result.onSuccess { showMsg(R.string.msg_file_decrypted) }
                .onFailure { e -> showMsg(e.message ?: getString(R.string.msg_decrypt_fail)) }
        }
    }

    /** bin 文件 -> 明文框（流式解密到内存，限大小） */
    private fun decryptFileToPlainText(file: android.net.Uri, key: String) {
        setProcessing(true)
        loadingSnackbar = Snackbar.make(binding.root, R.string.msg_file_decrypting, Snackbar.LENGTH_INDEFINITE)
        loadingSnackbar?.show()
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val ins = contentResolver.openInputStream(file) ?: throw IOException("无法打开文件")
                    val bos = java.io.ByteArrayOutputStream()
                    ins.use { FileCipher.decryptFile(it, bos, key) }
                    bos.toString(Charsets.UTF_8.name())
                }
            }
            setProcessing(false)
            loadingSnackbar?.dismiss()
            loadingSnackbar = null
            result.onSuccess { plain ->
                if (plain.length > maxFileBytes) {
                    showMsg(getString(R.string.msg_file_too_large, plain.length / 1024, maxFileBytes / 1024))
                } else {
                    binding.inputEdit.setText(plain)
                    showMsg(R.string.msg_decrypted)
                }
            }.onFailure { e ->
                showMsg(e.message ?: getString(R.string.msg_decrypt_fail))
            }
        }
    }

    /** txt 文件 -> Base64 密文框（读取限大小后加密） */
    private fun encryptFileToBase64(file: android.net.Uri, key: String) {
        setProcessing(true)
        loadingSnackbar = Snackbar.make(binding.root, R.string.msg_file_loading, Snackbar.LENGTH_INDEFINITE)
        loadingSnackbar?.show()
        lifecycleScope.launch {
            val result = runCatching {
                val text = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(file)?.use { it.bufferedReader(Charsets.UTF_8).readText() }
                } ?: throw IOException("无法打开文件")
                if (text.length > maxFileBytes) {
                    throw IllegalArgumentException(getString(R.string.msg_file_too_large, text.length / 1024, maxFileBytes / 1024))
                }
                withContext(Dispatchers.Default) { AesGcmCipher.encrypt(text, key) }
            }
            setProcessing(false)
            loadingSnackbar?.dismiss()
            loadingSnackbar = null
            result.onSuccess { cipher ->
                binding.cipherEdit.setText(cipher)
                copyToClipboard(cipher)
                showMsg(R.string.msg_encrypted_copied)
            }.onFailure { e ->
                showMsg(e.message ?: getString(R.string.msg_encrypt_fail))
            }
        }
    }

    /** 从系统文件选择器得到的 uri 中取显示名（用于建议输出文件名） */
    private fun displayName(uri: android.net.Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx) ?: "file"
            }
        }
        return "file"
    }

    /** 复制明文框内容 */
    private fun copyPlain() {
        val plain = binding.inputEdit.text?.toString().orEmpty()
        if (plain.isEmpty()) {
            showMsg(R.string.msg_no_plain)
            return
        }
        copyToClipboard(plain)
        showMsg(R.string.msg_copied)
    }

    /** 从剪贴板粘贴到明文框 */
    private fun pasteIntoPlain() {
        val text = clipboardText()
        if (text.isNullOrEmpty()) {
            showMsg(R.string.msg_clipboard_empty)
            return
        }
        binding.inputEdit.setText(text)
        binding.inputEdit.setSelection(text.length)
        showMsg(R.string.msg_pasted)
    }

    /** 从剪贴板粘贴到密文框（QQ / 微信复制的密文入口） */
    private fun pasteIntoCipher() {
        val text = clipboardText()
        if (text.isNullOrEmpty()) {
            showMsg(R.string.msg_clipboard_empty)
            return
        }
        binding.cipherEdit.setText(text)
        binding.cipherEdit.setSelection(text.length)
        showMsg(R.string.msg_pasted)
    }

    /** 一键复制密文 */
    private fun copyCipher() {
        val cipher = binding.cipherEdit.text?.toString().orEmpty()
        if (cipher.isEmpty()) {
            showMsg(R.string.msg_no_cipher)
            return
        }
        copyToClipboard(cipher)
        showMsg(R.string.msg_copied)
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("cipher-text", text))
    }

    private fun clipboardText(): String? {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return cm.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
    }

    private fun showMsg(resId: Int) = showMsg(getString(resId))

    private fun showMsg(msg: String) {
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
    }
}
