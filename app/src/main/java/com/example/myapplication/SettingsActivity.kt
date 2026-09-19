package com.example.myapplication

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.myapplication.databinding.ActivitySettingsBinding

/**
 * 设置页：文件模式开关（明文从 txt 加载 / 密文输出为 bin）。
 * 设置项存 SharedPreferences，主界面据此切换文本框显示与加解密路径。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyStatusBarInset()

        binding.toolbar.setNavigationOnClickListener { finish() }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        binding.swPlainFromTxt.isChecked = prefs.getBoolean(KEY_PLAIN_FROM_TXT, false)
        binding.swCipherToBin.isChecked = prefs.getBoolean(KEY_CIPHER_TO_BIN, false)

        binding.swPlainFromTxt.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_PLAIN_FROM_TXT, checked).apply()
        }
        binding.swCipherToBin.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_CIPHER_TO_BIN, checked).apply()
        }
    }

    /** Android 15+ 强制 edge-to-edge：根布局顶部加状态栏高度 padding */
    private fun applyStatusBarInset() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, 0)
            WindowInsetsCompat.CONSUMED
        }
    }

    companion object {
        const val PREFS_NAME = "settings"
        const val KEY_PLAIN_FROM_TXT = "plain_from_txt"
        const val KEY_CIPHER_TO_BIN = "cipher_to_bin"
    }
}
