package com.resqapp.myonesptote.utils.settings

import android.content.Context
import android.content.Intent
import android.widget.Button
import android.widget.Toast
import com.resqapp.myonesptote.R
import com.resqapp.myonesptote.screen.SpeechToTextActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// The class managing all SettingItems.
// Responsible for setting them all up, and applying all settings.
class SettingsPage(private val context: Context, private val btnApply: Button) {
    private val settingItems: ArrayList<SettingItem> = ArrayList<SettingItem>()

    fun add(settingItem: SettingItem) {
        settingItems.add(settingItem)
    }

    fun setup() {
        btnApply.setOnClickListener { apply() }

        CoroutineScope(Dispatchers.Main).launch {
            for (settingItem in settingItems) {
                settingItem.setup(context)
            }

            btnApply.isEnabled = false
        }
    }

    private fun apply() {
        CoroutineScope(Dispatchers.Main).launch {
            for (settingItem in settingItems) {
                settingItem.apply(context)
                settingItem.resetIsDirty()
            }

            btnApply.isEnabled = false
        }
        context.startActivity(Intent(context, SpeechToTextActivity::class.java))
        Toast.makeText(context, R.string.successfully_set, Toast.LENGTH_SHORT).show()
    }
}