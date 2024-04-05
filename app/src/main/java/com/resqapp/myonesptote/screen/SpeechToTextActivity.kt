package com.resqapp.myonesptote.screen

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.math.MathUtils
import com.resqapp.myonesptote.R
import com.resqapp.myonesptote.utils.button.BackspaceButton
import com.resqapp.myonesptote.utils.recorder.RecorderFSM
import com.resqapp.myonesptote.utils.recorder.RecorderManager
import com.resqapp.myonesptote.utils.whisper.WhisperInputService
import com.resqapp.myonesptote.utils.whisper.WhisperTranscriber
import kotlin.math.log10
import kotlin.math.pow

private const val AMPLITUDE_CLAMP_MIN: Int = 10
private const val AMPLITUDE_CLAMP_MAX: Int = 25000
private const val LOG_10_10: Float = 1.0F
private const val LOG_10_25000: Float = 4.398F
private const val AMPLITUDE_ANIMATION_DURATION: Long = 500
private val amplitudePowers: Array<Float> = arrayOf(0.5f, 1.0f, 2f, 3f)


const val RECORDED_AUDIO_FILENAME = "recorded.m4a"
const val AUDIO_MEDIA_TYPE = "audio/mp4"
private const val IME_SWITCH_OPTION_AVAILABILITY_API_LEVEL = 28

class SpeechToTextActivity : AppCompatActivity() {

    private enum class KeyboardStatus {
        Idle,             // Ready to start recording
        Recording,       // Currently recording
        Transcribing,    // Waiting for transcription results
    }

    // Keyboard event listeners. Assignable custom behaviors upon certain UI events (user-operated).
    private var onStartRecording: () -> Unit = { }
    private var onCancelRecording: () -> Unit = { }
    private var onStartTranscribing: (includeNewline: Boolean) -> Unit = { }
    private var onCancelTranscribing: () -> Unit = { }
    private var onButtonBackspace: () -> Unit = { }
    private var onSwitchIme: () -> Unit = { }
    private var onOpenSettings: () -> Unit = { }
    private var onEnter: () -> Unit = { }

    // Keyboard Status
    private var keyboardStatus: KeyboardStatus = KeyboardStatus.Idle

    private var keyboardView: ConstraintLayout? = null
    private var buttonMic: ImageButton? = null
    private var buttonEnter: ImageButton? = null
    private var buttonCancel: ImageButton? = null
    private var labelStatus: TextView? = null
    private var waitingIcon: ProgressBar? = null
    private var buttonBackspace: BackspaceButton? = null
    private var buttonPreviousIme: ImageButton? = null
    private var buttonSettings: ImageButton? = null
    private var micRippleContainer: ConstraintLayout? = null
    private var micRipples: Array<ImageView> = emptyArray()

    private var whisperTranscriber: WhisperTranscriber? = null
    private var recorderManager: RecorderManager? = null
    private var recorderFSM: RecorderFSM? = null


    private var isStart = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keyboardView = layoutInflater.inflate(R.layout.keyboard_view, null) as ConstraintLayout
        setContentView(keyboardView)

        whisperTranscriber = WhisperTranscriber()
        recorderManager = RecorderManager(this)
        recorderFSM = RecorderFSM(this)

        buttonMic = keyboardView!!.findViewById(R.id.btn_mic)!!
        buttonEnter = keyboardView!!.findViewById(R.id.btn_enter)!!
        buttonCancel = keyboardView!!.findViewById(R.id.btn_cancel)!!
        labelStatus = keyboardView!!.findViewById(R.id.label_status)!!
        waitingIcon = keyboardView!!.findViewById(R.id.pb_waiting_icon)!!
        buttonBackspace = keyboardView!!.findViewById(R.id.btn_backspace)!!
        buttonPreviousIme = keyboardView!!.findViewById(R.id.btn_previous_ime)!!
        buttonSettings = keyboardView!!.findViewById(R.id.btn_settings)!!
        micRippleContainer = keyboardView!!.findViewById(R.id.mic_ripples)!!

        buttonMic?.setOnClickListener {
            when (isStart) {
                true  -> {
                    Toast.makeText(this@SpeechToTextActivity, "should stop!", Toast.LENGTH_SHORT)
                        .show()

                    whisperTranscriber?.startAsync(this,
                        "${externalCacheDir?.absolutePath}/$RECORDED_AUDIO_FILENAME",
                        AUDIO_MEDIA_TYPE,
                        true,
                        {
                            Log.d("myThingss--->", "-->$it")
                            labelStatus?.text = it.toString()
                        },
                        {

                        })
                    recorderManager?.stop()
//                    whisperTranscriber?.stop()
                    tryCancelRecording()
//                    onCancelTranscribing()
                    isStart = false
                }

                false -> {

                    if (!recorderManager!!.allPermissionsGranted(this)) {
                        // If not, launch app MainActivity (for permission setup).

                        val dialogIntent = Intent(this, SpeechToTextActivity::class.java)
                        dialogIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(dialogIntent)

                        setKeyboardStatus(KeyboardStatus.Idle)
                    }


                    recorderManager?.start(
                        this,
                        "${externalCacheDir?.absolutePath}/$RECORDED_AUDIO_FILENAME"
                    )

                    tryStartTranscribing(false)
//
                    isStart = true
                }
            }
        }

    }


    fun setup(
        layoutInflater: LayoutInflater,
        shouldOfferImeSwitch: Boolean,
        onStartRecording: () -> Unit,
        onCancelRecording: () -> Unit,
        onStartTranscribing: (includeNewline: Boolean) -> Unit,
        onCancelTranscribing: () -> Unit,
        onButtonBackspace: () -> Unit,
        onEnter: () -> Unit,
        onSwitchIme: () -> Unit,
        onOpenSettings: () -> Unit
    ): View {
        // Inflate the keyboard layout & assign views
//        keyboardView = layoutInflater.inflate(R.layout.keyboard_view, null) as ConstraintLayout
        micRipples = arrayOf(
            keyboardView!!.findViewById(R.id.mic_ripple_0),
            keyboardView!!.findViewById(R.id.mic_ripple_1),
            keyboardView!!.findViewById(R.id.mic_ripple_2),
            keyboardView!!.findViewById(R.id.mic_ripple_3)
        )

        // Hide buttonPreviousIme if necessary
        if (!shouldOfferImeSwitch) {
            buttonPreviousIme!!.visibility = View.GONE
        }

        // Set onClick listeners
        buttonMic?.setOnClickListener { onButtonMicClick() }
        buttonEnter?.setOnClickListener { onButtonEnterClick() }
        buttonCancel?.setOnClickListener { onButtonCancelClick() }
        buttonSettings?.setOnClickListener { onButtonSettingsClick() }
        buttonBackspace?.setBackspaceCallback { onButtonBackspaceClick() }

        if (shouldOfferImeSwitch) {
            buttonPreviousIme!!.setOnClickListener { onButtonPreviousImeClick() }
        }

        // Set event listeners
        this.onStartRecording = onStartRecording
        this.onCancelRecording = onCancelRecording
        this.onStartTranscribing = onStartTranscribing
        this.onCancelTranscribing = onCancelTranscribing
        this.onButtonBackspace = onButtonBackspace
        this.onSwitchIme = onSwitchIme
        this.onOpenSettings = onOpenSettings
        this.onEnter = onEnter

        // Resets keyboard upon setup
        reset()

        // Returns the keyboard view (non-nullable)
        return keyboardView!!
    }

    private fun onButtonMicClick() {
        // Upon button mic click...
        // Idle -> Start Recording
        // Recording -> Finish Recording (without a newline)
        // Transcribing -> Nothing (to avoid double-clicking by mistake, which starts transcribing and then immediately cancels it)
        when (keyboardStatus) {
            KeyboardStatus.Idle         -> {
                setKeyboardStatus(KeyboardStatus.Recording)
                onStartRecording()
            }

            KeyboardStatus.Recording    -> {
                setKeyboardStatus(KeyboardStatus.Transcribing)
                onStartTranscribing(false)
            }

            KeyboardStatus.Transcribing -> {
                return
            }
        }
    }

    private fun onButtonEnterClick() {
        // Upon button enter click.
        // Recording -> Start transcribing (with a newline included)
        // else -> invokes onEnter
        if (keyboardStatus == KeyboardStatus.Recording) {
            setKeyboardStatus(KeyboardStatus.Transcribing)
            onStartTranscribing(true)
        } else {
            onEnter()
        }
    }

    private fun onButtonCancelClick() {
        // Upon button cancel click.
        // Recording -> Cancel
        // Transcribing -> Cancel
        // else -> nothing
        if (keyboardStatus == KeyboardStatus.Recording) {
            setKeyboardStatus(KeyboardStatus.Idle)
            onCancelRecording()
        } else if (keyboardStatus == KeyboardStatus.Transcribing) {
            setKeyboardStatus(KeyboardStatus.Idle)
            onCancelTranscribing()
        }
    }

    private fun onButtonSettingsClick() {
        // Currently, this onClick only makes a call to onOpenSettings()
        this.onOpenSettings()
    }

    private fun onButtonBackspaceClick() {
        // Currently, this onClick only makes a call to onButtonBackspace()
        this.onButtonBackspace()
    }

    private fun onButtonPreviousImeClick() {
        // Currently, this onClick only makes a call to onSwitchIme()
        this.onSwitchIme()
    }

    fun reset() {
        setKeyboardStatus(KeyboardStatus.Idle)
    }

    private fun setKeyboardStatus(newStatus: KeyboardStatus) {
        if (keyboardStatus == newStatus) {
            return
        }

        when (newStatus) {
            KeyboardStatus.Idle         -> {
                labelStatus!!.setText(R.string.whisper_to_input)
                buttonMic!!.setImageResource(R.drawable.mic_idle)
                waitingIcon!!.visibility = View.INVISIBLE
                buttonCancel!!.visibility = View.INVISIBLE
                micRippleContainer!!.visibility = View.GONE
            }

            KeyboardStatus.Recording    -> {
                labelStatus!!.setText(R.string.recording)
                buttonMic!!.setImageResource(R.drawable.mic_pressed)
                waitingIcon!!.visibility = View.INVISIBLE
                buttonCancel!!.visibility = View.VISIBLE
                micRippleContainer!!.visibility = View.VISIBLE
            }

            KeyboardStatus.Transcribing -> {
                labelStatus!!.setText(R.string.transcribing)
                buttonMic!!.setImageResource(R.drawable.mic_transcribing)
                waitingIcon!!.visibility = View.VISIBLE
                buttonCancel!!.visibility = View.VISIBLE
                micRippleContainer!!.visibility = View.GONE
            }
        }

        keyboardStatus = newStatus
    }

    fun updateMicrophoneAmplitude(amplitude: Int) {
        if (keyboardStatus != KeyboardStatus.Recording) {
            return
        }

        val clampedAmplitude = MathUtils.clamp(
            amplitude,
            AMPLITUDE_CLAMP_MIN,
            AMPLITUDE_CLAMP_MAX
        )

        // decibel-like calculation
        val normalizedPower =
            (log10(clampedAmplitude * 1f) - LOG_10_10) / (LOG_10_25000 - LOG_10_10)

        // normalizedPower ranges from 0 to 1.
        // The inner-most ripple should be the most sensitive to audio,
        // represented by a gamma-correction-like curve.
        for (micRippleIdx in micRipples.indices) {
            micRipples[micRippleIdx].clearAnimation()
            micRipples[micRippleIdx].alpha = normalizedPower.pow(amplitudePowers[micRippleIdx])
            micRipples[micRippleIdx].animate().alpha(0f).setDuration(AMPLITUDE_ANIMATION_DURATION)
                .start()
        }
    }

    fun tryCancelRecording() {
        if (keyboardStatus == KeyboardStatus.Recording) {
            setKeyboardStatus(KeyboardStatus.Idle)
            onCancelRecording()
        }
    }

    fun tryStartTranscribing(includeNewline: Boolean) {
        if (keyboardStatus == KeyboardStatus.Recording) {
            setKeyboardStatus(KeyboardStatus.Transcribing)
            onStartTranscribing(includeNewline)
        }
    }

    fun tryStartRecording() {
        if (keyboardStatus == KeyboardStatus.Idle) {
            setKeyboardStatus(KeyboardStatus.Recording)
            onStartRecording()
        }
    }


}