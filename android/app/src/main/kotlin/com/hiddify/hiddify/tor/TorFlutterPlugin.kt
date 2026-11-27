package com.hiddify.hiddify.tor

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.hiddify.hiddify.Settings
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

class TorFlutterPlugin : FlutterPlugin, MethodChannel.MethodCallHandler, EventChannel.StreamHandler {
    private lateinit var context: Context
    private var methods: MethodChannel? = null
    private var events: EventChannel? = null
    private var sink: EventChannel.EventSink? = null
    private lateinit var manager: TorEngineManager
    private lateinit var vault: BridgeVault
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        manager = TorEngineManager.get(context)
        vault = BridgeVault(context)
        methods = MethodChannel(binding.binaryMessenger, "visionvpn.tor/method").also {
            it.setMethodCallHandler(this)
        }
        events = EventChannel(binding.binaryMessenger, "visionvpn.tor/events").also {
            it.setStreamHandler(this)
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methods?.setMethodCallHandler(null)
        events?.setStreamHandler(null)
        methods = null
        events = null
        sink = null
    }

    override fun onListen(arguments: Any?, eventSink: EventChannel.EventSink?) {
        sink = eventSink
        manager.setListener { snapshot ->
            val payload = snapshot.toMap()
            mainHandler.post {
                sink?.success(payload)
            }
        }
    }

    override fun onCancel(arguments: Any?) {
        sink = null
        manager.setListener(null)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            when (call.method) {
                "start" -> {
                    val supplied = call.argument<List<String>>("bridges") ?: emptyList()
                    val transportMode = call.argument<String>("transportMode")
                    val bridges = if (supplied.isNotEmpty()) supplied else vault.load()
                    Settings.torModeEnabled = true
                    manager.start(bridges, transportMode)
                    result.success(manager.snapshot().toMap())
                }
                "recover" -> {
                    val supplied = call.argument<List<String>>("bridges") ?: emptyList()
                    val bridges = if (supplied.isNotEmpty()) supplied else vault.load()
                    manager.recover(bridges)
                    result.success(manager.snapshot().toMap())
                }
                "setTransportMode" -> {
                    val mode = call.argument<String>("transportMode") ?: TorTransportConfig.MODE_AUTOMATIC
                    manager.setTransportMode(mode)
                    result.success(manager.snapshot().toMap())
                }
                "setTorModeEnabled" -> {
                    val enabled = call.argument<Boolean>("enabled") ?: false
                    Settings.torModeEnabled = enabled
                    result.success(true)
                }
                "capabilities" -> {
                    val caps = manager.capabilities()
                    result.success(
                        mapOf(
                            "snowflakeAvailable" to caps.snowflakeAvailable,
                            "obfs4Available" to caps.obfs4Available,
                        )
                    )
                }
                "saveBridges" -> {
                    val bridges = call.argument<List<String>>("bridges") ?: emptyList()
                    val validated = bridges
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .map(TorTransportConfig::normalizeAndValidateBridgeLine)
                    vault.save(validated)
                    result.success(true)
                }
                "loadBridges" -> result.success(vault.load())
                "clearBridges" -> {
                    vault.clear()
                    result.success(true)
                }
                "stop" -> {
                    manager.stop()
                    result.success(true)
                }
                "triggerFailClosed" -> {
                    val reason = call.argument<String>("reason")
                        ?: "Manual fail-closed trigger for verification"
                    manager.triggerFailClosed(reason)
                    result.success(manager.snapshot().toMap())
                }
                "status" -> result.success(manager.snapshot().toMap())
                else -> result.notImplemented()
            }
        } catch (t: Throwable) {
            result.error("TOR_ERROR", t.message, null)
        }
    }
}
