package com.example.triggersensor.presentation

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Build
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.CalendarContract.CalendarAlerts
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.ViewModel
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.example.triggersensor.presentation.theme.TriggerSensorTheme
import java.io.File
import java.io.FileOutputStream
import java.io.Serializable
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.Timer
import java.util.TimerTask

@SuppressLint("StaticFieldLeak")
val triggerListener = TriggerListener()

class MainActivity : ComponentActivity() {
    private lateinit var sensorManager: SensorManager
    private lateinit var triggerSensor: Sensor
//    private lateinit var triggerListener: TriggerListener
    private lateinit var accSensor: Sensor
    private var accListener: AccListener = AccListener()
    private var viewModel = MainViewModel()
    private var dir = SimpleDateFormat("yyyy-MM-dd_HH_mm_ss", Locale.ENGLISH).format(Date())
    private lateinit var logFile: File
    private lateinit var accFileStream: FileOutputStream
    private val timerLengthMins: Float = 1.0F
    private lateinit var wakeLock: PowerManager.WakeLock
    private val requestTriggerAlarm: RequestTriggerAlarm = RequestTriggerAlarm()


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WearApp("${viewModel.nSamples}\n${viewModel.text}")
        }

        File(this.filesDir, dir).mkdir()

        logFile = File(this.filesDir, "$dir/Log.txt")
        accFileStream = FileOutputStream(File(this.filesDir, "$dir/data.txt"))
        accFileStream.write("timestamp,x,y,z,real_time_ms\n".toByteArray())
        logFile.writeText("real_time_ms,event\n")
        logFile.writeText("${Calendar.getInstance().timeInMillis},OnCreate()\n")
        Log.d("TriggerSensorState", "onCreate")

        val powerManager: PowerManager = getSystemService(ComponentActivity.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "TriggerSensor:keep_screen_on")

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
//        accListener.register()

        triggerListener.setValues(
            this,
            logFile,
            viewModel,
            accListener,
            wakeLock,
            timerLengthMins,
            TriggerTimerTask()
        )
        triggerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
        sensorManager.requestTriggerSensor(triggerListener, triggerSensor)

//        accListener.unregister()

        // Set recurring timer - request trigger sensor every 5 minutes
        requestTriggerAlarm.setAlarm(this, dir)
    }

    inner class AccListener : SensorEventListener{
        fun register() {
            logFile.writeText("${Calendar.getInstance().timeInMillis},Acc start\n")
            Log.d("TriggerSensorState", "Acc start")
            accSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val samplingPeriodMicroseconds = 1000000/100
            sensorManager.registerListener(this@AccListener, accSensor, samplingPeriodMicroseconds)
        }
        fun unregister() {
            sensorManager.unregisterListener(this@AccListener)
            Log.d("TriggerSensorState", "Acc stop")
            logFile.writeText("${Calendar.getInstance().timeInMillis},Acc stop\n")
        }

        override fun onSensorChanged(event: SensorEvent) {
            Log.d("TriggerSensorOnChange", "${event.values[0]}")
            viewModel.updateNSamples(viewModel.nSamples + 1)
            accFileStream.write("${event.timestamp},${event.values[0]},${event.values[1]},${event.values[2]},${Calendar.getInstance().timeInMillis}\n".toByteArray())
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        }
    }

    inner class TriggerTimerTask : TimerTask() {
        override fun run() {
            // unregister accelerometer, reset trigger, and let go of screen lock
            Log.d("TriggerSensorState", "Timer End")
            logFile.writeText("${Calendar.getInstance().timeInMillis},Timer End\n")
            viewModel.updateText("Timer End")

            sensorManager.cancelTriggerSensor(triggerListener, triggerSensor)
            sensorManager.requestTriggerSensor(triggerListener, triggerSensor)

            accListener.unregister()

            // this is handled by wakelock for now
//            runOnUiThread {
//                Log.d("TriggerSensorState", "Screen flag clear")
//                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
//            }

            // on timer end, vibrate watch for 0.5s
            var vibrator: Vibrator

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                var vibratorManager: VibratorManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibrator = vibratorManager.defaultVibrator
            } else {
                vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(VibrationEffect.createOneShot((0.5 * 1e3).toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    override fun onStart() {
        super.onStart()
        logFile.writeText("${Calendar.getInstance().timeInMillis},OnStart()\n")
        Log.d("TriggerSensorState", "onStart")
    }
    override fun onResume() {
        super.onResume()
        Log.d("TriggerSensorState", "onResume")

        sensorManager.cancelTriggerSensor(triggerListener, triggerSensor)
        sensorManager.requestTriggerSensor(triggerListener, triggerSensor)

        logFile.writeText("${Calendar.getInstance().timeInMillis},OnResume()\n")
    }
    override fun onPause() {
        super.onPause()
        logFile.writeText("${Calendar.getInstance().timeInMillis},onPause()\n")
        Log.d("TriggerSensorState", "onPause")
    }
    override fun onStop() {
        super.onStop()
        logFile.writeText("${Calendar.getInstance().timeInMillis},OnStop()\n")
        Log.d("TriggerSensorState", "onStop")
        viewModel.updateX(0F)
    }
    override fun onDestroy() {
        super.onDestroy()
        logFile.writeText("${Calendar.getInstance().timeInMillis},onDestroy()\n")
        Log.d("TriggerSensorState", "onDestroy")
//        accFileStream.close()
        requestTriggerAlarm.cancelAlarm(this)
    }
    override fun onRestart() {
        super.onRestart()
        logFile.writeText("${Calendar.getInstance().timeInMillis},onRestart()\n")
        Log.d("TriggerSensorState", "onRestart")
    }
}

//class Data(
//    val dir: String,
////    val viewModel: MainViewModel,
//    val logFile: File,
////    val sensorManager: SensorManager,
//    val triggerListener: MainActivity.TriggerListener
//) : Serializable {}

class RequestTriggerAlarm : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Log.d("TriggerSensorState", "RequestTriggerAlarm")
        var dir = intent?.getStringExtra("dir")
        File(context.filesDir, "$dir/Log.txt").writeText("${Calendar.getInstance().timeInMillis},RequestTriggerAlarm")

        var sensorManager = context.getSystemService(ComponentActivity.SENSOR_SERVICE) as SensorManager
        var triggerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)


        sensorManager.cancelTriggerSensor(triggerListener, triggerSensor)
        sensorManager.requestTriggerSensor(triggerListener, triggerSensor)

    }

    fun setAlarm(context: Context, dir: String) {
        var alarmManager: AlarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        var i: Intent = Intent(context, RequestTriggerAlarm::class.java)
        i.putExtra("dir", dir)
        i.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        var pi: PendingIntent = PendingIntent.getBroadcast(context, 0, i,  PendingIntent.FLAG_IMMUTABLE)
        alarmManager.setRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime()+1, (5 * 60 * 1e3).toLong(), pi)
    }

    fun cancelAlarm(context: Context) {
        var alarmManager: AlarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        var i: Intent = Intent(context, RequestTriggerAlarm::class.java)
        var sender: PendingIntent = PendingIntent.getBroadcast(context, 0, i,  PendingIntent.FLAG_IMMUTABLE)
        alarmManager.cancel(sender)
    }
}

class MainViewModel : ViewModel() {
    var x by mutableStateOf(0F)
    var nSamples by mutableStateOf(0)
    var text by mutableStateOf("Created")

    fun updateX(xValue: Float) {
        x = xValue
    }
    fun updateNSamples(_nSamples: Int) {
        nSamples = _nSamples
    }
    fun updateText(_text: String) {
        text = _text
    }
}

@Composable
fun WearApp(text: String) {
    TriggerSensorTheme {
        /* If you have enough items in your list, use [ScalingLazyColumn] which is an optimized
         * version of LazyColumn for wear devices with some added features. For more information,
         * see d.android.com/wear/compose.
         */
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background),
            verticalArrangement = Arrangement.Center
        ) {
            Greeting(text)
        }
    }
}

@Composable
fun Greeting(text: String) {
    Text(
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
        color = MaterialTheme.colors.primary,
        text = "Trigger Sensor\nnSamples: $text"
    )
}