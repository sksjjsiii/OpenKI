package com.andoco.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val methodChannelName = "andoco/control"
    private val eventChannelName = "andoco/logs"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, methodChannelName)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "startService" -> {
                        val intent = Intent(this, OpencodeService::class.java).apply {
                            action = OpencodeService.ACTION_START
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(intent)
                        } else {
                            startService(intent)
                        }
                        result.success(null)
                    }
                    "stopService" -> {
                        val intent = Intent(this, OpencodeService::class.java).apply {
                            action = OpencodeService.ACTION_STOP
                        }
                        startService(intent)
                        result.success(null)
                    }
                    "reloadService" -> {
                        val intent = Intent(this, OpencodeService::class.java).apply {
                            action = OpencodeService.ACTION_RELOAD
                        }
                        startService(intent)
                        result.success(null)
                    }
                    "isServerReady" -> {
                        result.success(OpencodeService.serverReady)
                    }
                    else -> result.notImplemented()
                }
            }

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, eventChannelName)
            .setStreamHandler(object : EventChannel.StreamHandler {
                private var receiver: android.content.BroadcastReceiver? = null

                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    val filter = android.content.IntentFilter(OpencodeService.ACTION_LOG)
                    receiver = object : android.content.BroadcastReceiver() {
                        override fun onReceive(context: Context?, intent: Intent?) {
                            val line = intent?.getStringExtra(OpencodeService.EXTRA_LINE) ?: return
                            events?.success(line)
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                    } else {
                        @Suppress("DEPRECATION")
                        registerReceiver(receiver, filter)
                    }
                }

                override fun onCancel(arguments: Any?) {
                    receiver?.let { unregisterReceiver(it) }
                    receiver = null
                }
            })
    }
}
