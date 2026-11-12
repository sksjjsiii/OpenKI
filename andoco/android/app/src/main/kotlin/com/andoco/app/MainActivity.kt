package com.andoco.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val methodChannelName = "opencode/control"
    private val eventChannelName = "opencode/logs"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        flutterEngine
            .platformViewsController
            .registry
            .registerViewFactory("termux-terminal", TermuxTerminalViewFactory(flutterEngine.dartExecutor.binaryMessenger))

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, methodChannelName)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "startService" -> {
                        val intent = Intent(this, OpencodeService::class.java).apply { action = OpencodeService.ACTION_START }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
                        result.success(null)
                    }
                    "stopService" -> {
                        val intent = Intent(this, OpencodeService::class.java).apply { action = OpencodeService.ACTION_STOP }
                        startService(intent); result.success(null)
                    }
                    "reload", "reloadService" -> {
                        val intent = Intent(this, OpencodeService::class.java).apply { action = OpencodeService.ACTION_RELOAD }
                        startService(intent); result.success(null)
                    }
                    "isServerReady" -> result.success(OpencodeService.serverReady)
                    "isBootstrapped" -> result.success(OpencodeService.bootstrapped)
                    "getStatus" -> result.success(OpencodeService.getStatus())
                    "sendInput" -> {
                        val input = call.argument<String>("input") ?: ""
                        val session = call.argument<String>("session") ?: OpencodeService.MAIN_SESSION
                        OpencodeService.sendInput(input, session)
                        result.success(null)
                    }
                    "createSession" -> result.success(OpencodeService.createSession())
                    "closeSession" -> {
                        val session = call.argument<String>("session") ?: ""
                        if (session.isNotEmpty()) OpencodeService.closeSession(session)
                        result.success(null)
                    }
                    "listSessions" -> result.success(OpencodeService.listSessions())
                    else -> result.notImplemented()
                }
            }

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, eventChannelName)
            .setStreamHandler(object : EventChannel.StreamHandler {
                private var receiver: android.content.BroadcastReceiver? = null
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    val reqSession = arguments as? String
                    try {
                        val snapshots = if (reqSession != null) listOf(reqSession to (OpencodeService.sessionBuffers[reqSession]?.toList() ?: emptyList()))
                            else OpencodeService.sessionBuffers.map { it.key to it.value.toList() }
                        for ((sid, lines) in snapshots) for (line in lines) events?.success(mapOf("session" to sid, "line" to line))
                    } catch (_: Exception) { }
                    val filter = android.content.IntentFilter(OpencodeService.ACTION_LOG)
                    receiver = object : android.content.BroadcastReceiver() {
                        override fun onReceive(context: android.content.Context?, intent: Intent?) {
                            val line = intent?.getStringExtra(OpencodeService.EXTRA_LINE) ?: return
                            val sid = intent.getStringExtra(OpencodeService.EXTRA_SESSION) ?: OpencodeService.MAIN_SESSION
                            if (reqSession != null && reqSession != sid) return
                            events?.success(mapOf("session" to sid, "line" to line))
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
                    else @Suppress("DEPRECATION") registerReceiver(receiver, filter)
                }
                override fun onCancel(arguments: Any?) { receiver?.let { unregisterReceiver(it) }; receiver = null }
            })
    }
}
