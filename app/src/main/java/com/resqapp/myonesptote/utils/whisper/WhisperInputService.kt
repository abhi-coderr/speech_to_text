/*
 * This file is part of Whisper To Input, see <https://github.com/j3soon/whisper-to-input>.
 *
 * Copyright (c) 2023 Yan-Bin Diau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.resqapp.myonesptote.utils.whisper

import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.View
import android.content.Intent
import android.os.IBinder
import android.text.TextUtils
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import com.github.liuyueyi.quick.transfer.ChineseUtils
import com.github.liuyueyi.quick.transfer.constants.TransType
import com.resqapp.myonesptote.screen.MainActivity
import com.resqapp.myonesptote.screen.SpeechToTextActivity
import com.resqapp.myonesptote.utils.recorder.RecorderFSM
import com.resqapp.myonesptote.utils.recorder.RecorderManager
import com.resqapp.myonesptote.utils.recorder.RecorderStateOutput

private const val RECORDED_AUDIO_FILENAME = "recorded.m4a"
private const val AUDIO_MEDIA_TYPE = "audio/mp4"
private const val IME_SWITCH_OPTION_AVAILABILITY_API_LEVEL = 28

class WhisperInputService : InputMethodService() {
    private val whisperKeyboard: SpeechToTextActivity = SpeechToTextActivity()
    private val whisperTranscriber: WhisperTranscriber = WhisperTranscriber()
    private var recorderManager: RecorderManager? = null
    private var recorderFsm: RecorderFSM? = null
    private var recordedAudioFilename: String = ""
    private var isFirstTime: Boolean = true

    private fun transcriptionCallback(text: String?) {

        Log.d("MyLogeTExt-->", "--->> $text")

        if (!text.isNullOrEmpty()) {
            currentInputConnection?.commitText(ChineseUtils.s2tw(text), 1)
        }
        whisperKeyboard.reset()
    }

    private fun transcriptionExceptionCallback(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onCreateInputView(): View {
        // Initialize members with regard to this context
        recorderManager = RecorderManager(this)
        recorderFsm = RecorderFSM(this)

        // Preload conversion table
        ChineseUtils.preLoad(true, TransType.SIMPLE_TO_TAIWAN)

        // Assigns the file name for recorded audio
        recordedAudioFilename = "${externalCacheDir?.absolutePath}/$RECORDED_AUDIO_FILENAME"

        // Should offer ime switch?
        val shouldOfferImeSwitch: Boolean =
            if (Build.VERSION.SDK_INT >= IME_SWITCH_OPTION_AVAILABILITY_API_LEVEL) {
                shouldOfferSwitchingToNextInputMethod()
            } else {
                val inputMethodManager =
                    getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                val token: IBinder? = window?.window?.attributes?.token
                inputMethodManager.shouldOfferSwitchingToNextInputMethod(token)
            }

        // Sets up recorder manager
        recorderManager!!.setOnUpdateMicrophoneAmplitude { amplitude ->
            onUpdateMicrophoneAmplitude(amplitude)
        }

        // Returns the keyboard after setting it up and inflating its layout
        return whisperKeyboard.setup(layoutInflater,
            shouldOfferImeSwitch,
            { onStartRecording() },
            { onCancelRecording() },
            { includeNewline -> onStartTranscription(includeNewline) },
            { onCancelTranscription() },
            { onDeleteText() },
            { onEnter() },
            { onSwitchIme() },
            { onOpenSettings() })
    }

     fun onStartRecording() {
        // Upon starting recording, check whether audio permission is granted.
        if (!recorderManager!!.allPermissionsGranted(this)) {
            // If not, launch app MainActivity (for permission setup).
            launchMainActivity()
            whisperKeyboard.reset()
            return
        }

        recorderManager!!.start(this, recordedAudioFilename)
        recorderFsm!!.reset()
    }

    private fun onUpdateMicrophoneAmplitude(amplitude: Int) {
        // Reports amplitude to fsm.
        when (recorderFsm!!.reportAmplitude(amplitude)) {
            // Normally, just update keyboard visuals
            RecorderStateOutput.Normal -> {
                whisperKeyboard.updateMicrophoneAmplitude(amplitude)
            }
            // If the fsm indicates cancellation,
            // simulates a click of the mic button to cancel recording
            RecorderStateOutput.CancelRecording -> {
                whisperKeyboard.tryCancelRecording()
            }
            // If the fsm indicates finish,
            // simulates a click of the done button to start transcribing
            RecorderStateOutput.FinishRecording -> {
                whisperKeyboard.tryStartTranscribing(false)
            }
        }
    }

    private fun onCancelRecording() {
        recorderManager!!.stop()
    }

    private fun onStartTranscription(includeNewline: Boolean) {
        recorderManager!!.stop()
        whisperTranscriber.startAsync(this,
            recordedAudioFilename,
            AUDIO_MEDIA_TYPE,
            includeNewline, {
                transcriptionCallback(it)
            },
            { transcriptionExceptionCallback(it) })
    }

    private fun onCancelTranscription() {
        whisperTranscriber.stop()
    }

    private fun onDeleteText() {
        val inputConnection = currentInputConnection ?: return
        val selectedText = inputConnection.getSelectedText(0)

        // Deletes cursor pointed text, or all selected texts
        if (TextUtils.isEmpty(selectedText)) {
            inputConnection.deleteSurroundingText(1, 0)
        } else {
            inputConnection.commitText("", 1)
        }
    }

    private fun onSwitchIme() {
        // Before API Level 28, switchToPreviousInputMethod() was not available
        if (Build.VERSION.SDK_INT >= IME_SWITCH_OPTION_AVAILABILITY_API_LEVEL) {
            switchToPreviousInputMethod()
        } else {
            val inputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            val token: IBinder? = window?.window?.attributes?.token
            inputMethodManager.switchToLastInputMethod(token)
        }

    }

    private fun onOpenSettings() {
        launchMainActivity()
    }

    private fun onEnter() {
        val inputConnection = currentInputConnection ?: return
        inputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
    }

    // Opens up app MainActivity
    private fun launchMainActivity() {
        val dialogIntent = Intent(this, MainActivity::class.java)
        dialogIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(dialogIntent)
    }

    override fun onWindowShown() {
        super.onWindowShown()
        whisperTranscriber.stop()
        whisperKeyboard.reset()
        recorderManager!!.stop()

        // If this is the first time calling onWindowShown, it means this IME is just being switched to
        // Automatically starts recording after switching to Whisper Input
        if (isFirstTime) {
            isFirstTime = false
            whisperKeyboard.tryStartRecording()
        }
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        whisperTranscriber.stop()
        whisperKeyboard.reset()
        whisperKeyboard.reset()
        recorderManager!!.stop()
    }

}
