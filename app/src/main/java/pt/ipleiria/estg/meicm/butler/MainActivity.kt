package pt.ipleiria.estg.meicm.butler

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.CountDownTimer
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.format.Formatter
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.MutableLiveData
import com.google.android.material.snackbar.Snackbar
import io.ktor.application.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.jetty.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject
import pl.droidsonroids.gif.GifDrawable
import pt.ipleiria.estg.meicm.butler.databinding.ActivityMainBinding
import java.time.LocalDateTime
import java.util.*


class MainActivity : AppCompatActivity(), RecognitionListener, TextToSpeech.OnInitListener {

    // TODO: Insert your server IP:port. E.g.: 192.168.1.1:7579
    private val serverIP = "CHANGE ME"
    private val serverURI = "http://" + this.serverIP

    private lateinit var deviceIp: String

    private val permissionsRequestRecordAudio = 1

    private var speech: SpeechRecognizer? = null
    private var recognizerIntent: Intent? = null

    private var detectedKeyword: Boolean = false
    private val keyword: String = "mordomo"

    private val LOGTAG = "VoiceRecognitionActivity"

    private lateinit var binding: ActivityMainBinding

    private var receivedLocationNotification: MutableLiveData<String> = MutableLiveData<String>()
    private var receivedSentenceNotification: MutableLiveData<String> = MutableLiveData<String>()
    private val managerContainerURI = "/onem2m/butler/iproomcnt"
    private val currentRoomContainerURI = "/onem2m/location/currentroomcnt"
    private val sentencesToSpeakContainerURI = "/onem2m/butler/speakcnt"
    private lateinit var roomName: String
    private var active: MutableLiveData<Boolean> = MutableLiveData<Boolean>()
    private val client: OkHttpClient = OkHttpClient()

    private var tts: TextToSpeech? = null
    private var runningSpeech: MutableLiveData<Boolean> = MutableLiveData()
    private var noAnswerSpeech: MutableLiveData<Boolean> = MutableLiveData()
    private var recognitionText: MutableLiveData<String> = MutableLiveData<String>()

    private var timer: CountDownTimer? = null
    private var lastAnimation: Action? = null

    private var isRuning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_main)

        active.postValue(false)
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        deviceIp = Formatter.formatIpAddress(wifiManager.connectionInfo.ipAddress)
        binding.progressBar1.visibility = View.INVISIBLE

        val audioManager: AudioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_NOTIFICATION,
            AudioManager.ADJUST_MUTE,
            0
        )
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            0
        )

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                permissionsRequestRecordAudio
            )
        }

        CoroutineScope(Dispatchers.Default).launch {
            checkRoomName()
            checkIfIsActive()
            checkSubs()
        }

        embeddedServer(Jetty, 1400) {
            routing {
                post("/location") {
                    val receiveText = call.receiveText()
                    Log.d("NOTIFICATION", receiveText)
                    receivedLocationNotification.postValue(receiveText)
                    call.respond(HttpStatusCode.OK)
                }
                post("/sentences") {
                    val receiveText = call.receiveText()
                    call.respond(HttpStatusCode.OK)
                    Log.d("NOTIFICATION", receiveText)
                    receivedSentenceNotification.postValue(receiveText)
                }
            }
        }.start(wait = false)


        runningSpeech.observeForever {
            if (it == false) {
                animate(Action.WAITING)
                speech!!.startListening(recognizerIntent)
            } else {
                animate(Action.TALK)
            }
        }

        receivedLocationNotification.observeForever {
            if (it != null) {
                readNotification("location", it)
            }
        }

        receivedSentenceNotification.observeForever {
            if (it != null && active.value == true) {
                readNotification("sentence", it)
            }
        }

        active.observeForever {
            if (it != null) {
                if (it) {
                    binding.progressBar1.visibility = View.VISIBLE
                    binding.progressBar1.isIndeterminate = true

                    animate(Action.TURN_ON)

                    resetSpeechRecognizer()
                    setRecogniserIntent()

                    tts = TextToSpeech(this, this)
                    tts!!.setOnUtteranceProgressListener(
                        SpeechListener(
                            runningSpeech,
                            noAnswerSpeech
                        )
                    )
                    speech!!.startListening(recognizerIntent)

                } else {
                    animate(Action.NOT_PRESENT)
                    timer?.cancel()
                    timer = null

                    binding.progressBar1.visibility = View.INVISIBLE
                    if (tts != null && speech != null) {
                        tts!!.stop()
                        speech!!.destroy()
                    }

                }
            }
        }

        recognitionText.observeForever {
            if (it != null) {
                sentenceToAnswer(it)
            }
        }

        noAnswerSpeech.observeForever {
            if (it) {
                animate(Action.SAD)
                startTimeCounterNoAnswer()
            }
        }
    }

    private fun checkSubs() {
        try {
            var responseContainer = query("$currentRoomContainerURI/$deviceIp")
            if (responseContainer != "Not found") {
                val resp = JSONObject(responseContainer)
                if (resp.has("m2m:dbg")) {
                    if (resp["m2m:dbg"] == "resource does not exist") {
                        subscribeCurrentLocation(Room(deviceIp, deviceIp))
                    }
                }
            }

            responseContainer = query("$sentencesToSpeakContainerURI/$deviceIp")
            if (responseContainer != "Not found") {
                val resp = JSONObject(responseContainer)
                if (resp.has("m2m:dbg")) {
                    if (resp["m2m:dbg"] == "resource does not exist") {
                        subscribeSentencesToSpeakContainer(Room(deviceIp, deviceIp))
                    }
                }
            }
        } catch (e: Exception) {
            showSnack("Failed to connect to server")
        }

    }

    private fun subscribeSentencesToSpeakContainer(room: Room) {
        val mediaType = "application/xml;ty=23".toMediaTypeOrNull()
        val body: RequestBody = RequestBody.create(
            mediaType,
            "<m2m:sub xmlns:m2m= \"http://www.onem2m.org/xml/protocols\" rn=\"${room.ip}\"><nu>http://${room.ip}:1400/sentences</nu><nct>2</nct><enc><net>3</net></enc></m2m:sub>"
        )
        val request: Request = makeRequest(
            serverURI + sentencesToSpeakContainerURI,
            body,
            "application/xml",
            "23",
            "0008"
        )

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                showSnack("Error subscribing sentences to speak container")
            }
        }
    }


    private fun subscribeCurrentLocation(room: Room) {
        val client: OkHttpClient = OkHttpClient().newBuilder()
            .build()
        val mediaType = "application/xml;ty=23".toMediaTypeOrNull()
        val body = RequestBody.create(
            mediaType,
            "<m2m:sub xmlns:m2m= \"http://www.onem2m.org/xml/protocols\" rn=\"${room.ip}\"><nu>http://${room.ip}:1400/location</nu><nct>2</nct><enc><net>3</net></enc></m2m:sub>"
        )

        val request: Request =
            makeRequest(serverURI + currentRoomContainerURI, body, "application/xml", "23", "0008")
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                showSnack("Error subscribing sentences to speak container")
            }
        }
    }

    private fun checkIfIsActive() {
        try {
            var responseContainer = query("$currentRoomContainerURI?fu=1&ty=4")
            if (responseContainer != "Not found " && responseContainer.isNotEmpty()) {

                var resp = JSONObject(responseContainer)
                val respArray = resp["m2m:uril"] as JSONArray
                if (respArray.length() > 0) {
                    responseContainer = query(respArray[0] as String)
                    resp = JSONObject(responseContainer)
                    if (resp.has("m2m:cin")) {
                        resp = resp.getJSONObject("m2m:cin")
                        if (resp.has("con")) {
                            if (resp.getString("con") == roomName) {
                                active.postValue(true)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Exception", e.toString())
        }
    }

    private fun readNotification(notfSource: String, notf: String) {
        try {
            var jsonObject = JSONObject(notf)
            var sur = ""
            if (jsonObject.has("m2m:sgn")) {
                jsonObject = jsonObject.getJSONObject("m2m:sgn")
                if (jsonObject.has("sur")) {
                    sur = jsonObject.getString("sur")
                    if (jsonObject.has("nev")) {
                        jsonObject = jsonObject.getJSONObject("nev")
                        if (jsonObject.has("rep")) {
                            jsonObject = jsonObject.getJSONObject("rep")
                            if (jsonObject.has("m2m:cin")) {
                                jsonObject = jsonObject.getJSONObject("m2m:cin")
                                if (notfSource == "location") {
                                    if (sur == "$currentRoomContainerURI/$deviceIp" && jsonObject.getString(
                                            "ty"
                                        ).toInt() == 4
                                    ) {
                                        active.postValue(
                                            jsonObject.getString("con")
                                                .equals(roomName, ignoreCase = true)
                                        )
                                    }
                                }
                                if (notfSource == "sentence") {
                                    if (sur == "$sentencesToSpeakContainerURI/$deviceIp" && jsonObject.getString(
                                            "ty"
                                        ).toInt() == 4
                                    ) {

                                        detectedKeyword = false
                                        println(speech.toString())

                                        tts!!.speak(
                                            jsonObject.getString("con"),
                                            TextToSpeech.QUEUE_FLUSH,
                                            null,
                                            "1"
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ERROR", "XML")
        }

    }

    private fun checkRoomName() {
        try {
            val responseContainer = query("$managerContainerURI/$deviceIp")
            if (responseContainer != "Not found" && responseContainer.isNotEmpty()) {
                var resp = JSONObject(responseContainer)
                if (resp.has("m2m:dbg")) {
                    if (resp["m2m:dbg"] == "resource does not exist") {
                        showSnack("There are no room for this device")
                    }
                }

                if (resp.has("m2m:cin")) {
                    resp = resp.getJSONObject("m2m:cin")
                    if (resp.has("rn") && resp.has("con")) {
                        if (resp.getString("rn") == deviceIp) {
                            roomName = resp.getString("con")
                            binding.roomNameTv.text = roomName.capitalize(Locale.ROOT)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Exception", e.toString())
        }

    }

    private fun sentenceToAnswer(answer: String) {
        if (answer.toLowerCase(Locale.ROOT).contains("horas são") || answer.toLowerCase(Locale.ROOT)
                .contains("são que horas")
        ) {
            animate(Action.TALK)
            val current = LocalDateTime.now()
            tts!!.speak(
                "são ${current.hour} horas e ${current.minute} minutos",
                TextToSpeech.QUEUE_FLUSH,
                null,
                ""
            )


        } else if (answer.toLowerCase(Locale.ROOT).contains("dia é hoje") || answer.toLowerCase(
                Locale.ROOT
            )
                .contains("hoje é que dia")
        ) {
            animate(Action.TALK)
            val current = LocalDateTime.now()
            tts!!.speak(
                mappingDays(current.dayOfWeek.toString()),
                TextToSpeech.QUEUE_FLUSH,
                null,
                ""
            )

        } else {
            //speech!!.startListening(recognizerIntent)
            tts!!.speak("Não reconheço esse comando", TextToSpeech.QUEUE_FLUSH, null, "99")
        }
    }

    private fun mappingDays(day: String): String {
        when (day.toLowerCase(Locale.ROOT)) {
            "monday" -> return "segunda"
            "tuesday" -> return "terça"
            "wednesday" -> return "quarta"
            "thursday" -> return "quinta"
            "friday" -> return "sexta"
            "saturday" -> return "sábado"
            "sunday" -> return "domingo"
        }
        return "Eu não sei"
    }

    private fun query(parameters: String): String {
        var responseToReturn = ""
        try {
            val request: Request = Request.Builder()
                .url(serverURI + parameters)
                .addHeader("Accept", "application/json")
                .addHeader("X-M2m-RI", "00001")
                .build()
            client.newCall(request).execute().use { response ->
                responseToReturn = if (!response.isSuccessful && response.code != 404) {
                    "Not found"
                } else {
                    response.body?.string() ?: ""
                }
            }
        } catch (e: Exception) {
            showSnack("Query Request failed")
        }
        return responseToReturn
    }

    private fun showSnack(message: String) {
        val snack =
            Snackbar.make(this.findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG)
        snack.setAction("Dismiss") { snack.dismiss() }
        snack.show()
    }

    private fun resetSpeechRecognizer() {
        speech?.destroy()
        speech = SpeechRecognizer.createSpeechRecognizer(this)
        Log.i(LOGTAG, "isRecognitionAvailable: " + SpeechRecognizer.isRecognitionAvailable(this))
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speech!!.setRecognitionListener(this)
        } else finish()
    }

    private fun setRecogniserIntent() {
        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        recognizerIntent!!.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,
            Locale.getDefault()
        )
        recognizerIntent!!.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )
        recognizerIntent!!.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }


    override fun onResume() {
        Log.i(LOGTAG, "resume")
        super.onResume()

    }

    override fun onPause() {
        Log.i(LOGTAG, "pause")
        super.onPause()

    }

    override fun onStop() {
        Log.i(LOGTAG, "stop")
        super.onStop()
    }


    override fun onBeginningOfSpeech() {
        Log.i(LOGTAG, "onBeginningOfSpeech")
        binding.progressBar1.isIndeterminate = false
        binding.progressBar1.max = 10
    }

    override fun onBufferReceived(buffer: ByteArray) {
        Log.i(LOGTAG, "onBufferReceived: $buffer")
    }

    override fun onEndOfSpeech() {
        Log.i(LOGTAG, "onEndOfSpeech")
        binding.progressBar1.isIndeterminate = true
        speech!!.stopListening()
    }

    override fun onResults(results: Bundle) {
        Log.i(LOGTAG, "onResults")
        val matches = results
            .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        println(matches!![0])

        timer?.cancel()
        timer = null
        isRuning = false
        Log.e("RESULTS", "RESULTS")

        if (detectedKeyword && matches.size != 0) {

            if (matches[0].contains(keyword) || matches[0].contains("Morgan") || matches[0].contains(
                    "mordam"
                ) || matches[0].contains("perdão") || matches[0].contains("cordão")
            ) {
                tts!!.speak("Diga", TextToSpeech.QUEUE_FLUSH, null, "")
            } else {
                //se for um comando, tenta responder
                detectedKeyword = false
                sentenceToAnswer(matches[0])

            }

        } else if (matches[0].contains(keyword) || matches[0].contains("Morgan") || matches[0].contains(
                "mordam"
            ) || matches[0].contains("perdão") || matches[0].contains("cordão")
        ) {
            detectedKeyword = true
            tts!!.speak("Diga", TextToSpeech.QUEUE_FLUSH, null, "")
        } else {
            speech!!.startListening(recognizerIntent)
        }
    }

    override fun onError(errorCode: Int) {
        val errorMessage = getErrorText(errorCode)
        Log.i(LOGTAG, "FAILED $errorMessage")

        if (errorCode == SpeechRecognizer.ERROR_NO_MATCH) {
            if (!isRuning && lastAnimation != Action.SLEEP) {
                Log.e("ERROR RECOGGGG", "ERROR")
                startTimeCounterSleep()
            }
            speech!!.startListening(recognizerIntent)
        }

        if (errorCode == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
            speech!!.stopListening()
        }

        if (errorCode == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            resetSpeechRecognizer()
            speech!!.startListening(recognizerIntent)
        }
    }

    override fun onEvent(arg0: Int, arg1: Bundle?) {
        Log.i(LOGTAG, "onEvent")
    }

    override fun onPartialResults(arg0: Bundle?) {
        Log.i(LOGTAG, "onPartialResults")
    }

    override fun onReadyForSpeech(arg0: Bundle?) {
        Log.i(LOGTAG, "onReadyForSpeech")
    }

    override fun onRmsChanged(rmsdB: Float) {
        if (rmsdB > 5 && rmsdB < 10.0 && (lastAnimation == null || lastAnimation == Action.SLEEP || lastAnimation == Action.TALK || lastAnimation == Action.WAITING) && !tts?.isSpeaking!!) {
            animate(Action.IMPATIENT)
        }

        if (rmsdB > 5 && rmsdB < 10.0) {
            Log.e("RMSDB", rmsdB.toString())
        }


        binding.progressBar1.progress = rmsdB.toInt()
    }

    private fun getErrorText(errorCode: Int): String {
        return when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Client side error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No match"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RecognitionService busy"
            SpeechRecognizer.ERROR_SERVER -> "error from server"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
            else -> "Didn't understand, please try again."
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts!!.setLanguage(Locale.getDefault())

            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTS", "The Language specified is not supported!")
            }

        } else {
            Log.e("TTS", "Initilization Failed!")
        }
    }


    private fun animate(action: Action) {
        val resource: Int
        lastAnimation = action

        if (action == Action.WAITING || action == Action.IMPATIENT || action == Action.NOT_PRESENT) {
            binding.gifAction.visibility = View.GONE

            resource = resources.getIdentifier(
                "arci_" + action.name.toLowerCase(Locale.ROOT),
                "drawable", packageName
            )
            binding.imageAction.setImageResource(resource)

            binding.imageAction.visibility = View.VISIBLE
        } else {
            binding.imageAction.visibility = View.GONE

            resource = resources.getIdentifier(
                "arci_anim_" + action.name.toLowerCase(Locale.ROOT),
                "drawable", packageName
            )
            binding.gifAction.setImageResource(resource)

            if (action == Action.TURN_ON || action == Action.SAD) (binding.gifAction.drawable as GifDrawable).loopCount =
                1

            binding.gifAction.visibility = View.VISIBLE
        }
    }

    private fun startTimeCounterSleep() {
        isRuning = true
        timer = object : CountDownTimer(15000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                println(millisUntilFinished)
            }

            override fun onFinish() {
                if (!tts?.isSpeaking!! && lastAnimation != Action.SLEEP)
                    animate(Action.SLEEP)
                isRuning = false
            }
        }.start()

    }

    private fun startTimeCounterNoAnswer() {

        timer = object : CountDownTimer(1500, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                println(millisUntilFinished)
            }

            override fun onFinish() {
                animate(Action.WAITING)
                speech?.startListening(recognizerIntent)
            }
        }.start()

    }

    private fun makeRequest(
        url: String,
        body: RequestBody,
        type: String,
        ty: String,
        XM2MRI: String
    ): Request {
        return Request.Builder()
            .url(url)
            .method("POST", body)
            .addHeader("Content-Type", "$type; ty=$ty")
            .addHeader("X-M2M-RI", XM2MRI)
            .addHeader("Authorization", "Basic c3VwZXJhZG1pbjpzbWFydGhvbWUyMQ==")
            .build()
    }
}
