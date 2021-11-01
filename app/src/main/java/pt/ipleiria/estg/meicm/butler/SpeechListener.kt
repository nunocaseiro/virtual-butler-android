package pt.ipleiria.estg.meicm.butler

import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.lifecycle.MutableLiveData

class SpeechListener(
    var b: MutableLiveData<Boolean>,
    var noAnswer: MutableLiveData<Boolean>
) :
    UtteranceProgressListener() {


    override fun onDone(utteranceId: String?) {
        if (utteranceId == "99") {
            noAnswer.postValue(true)
        } else {
            b.postValue(false)
        }

    }

    override fun onError(utteranceId: String?) {
        Log.e("TTS ERROR", "ERROR ")
    }

    override fun onStart(utteranceId: String?) {
        b.postValue(true)
        Log.e("TTS START", "START TTS ")
    }
}