package cn.toside.music.mobile.smb

import android.content.Intent
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableArray
import com.facebook.react.modules.core.DeviceEventManagerModule
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * SMB 备份能力原生模块。
 * JS 侧通过 NativeModules.SmbModule 调用。
 */
class SmbModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private val manager = SmbManager()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun getName(): String = "SmbModule"

    private fun emitProgress(taskId: String?, transferred: Long, total: Long) {
        if (taskId.isNullOrEmpty()) return
        try {
            val body = Arguments.createMap().apply {
                putString("taskId", taskId)
                putDouble("transferred", transferred.toDouble())
                putDouble("total", total.toDouble())
            }
            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit("SmbProgress", body)
        } catch (t: Throwable) {
            // 进度事件发送失败不影响传输本身
            android.util.Log.w("SmbModule", "emit progress failed: ${t.message}")
        }
    }

    private fun reject(promise: Promise, error: Throwable) {
        val message = error.message ?: error.toString()
        promise.reject("SMB_ERROR", message, error)
    }

    private fun runAsync(promise: Promise, action: () -> Unit) {
        executor.execute {
            try {
                action()
            } catch (e: Throwable) {
                reject(promise, e)
            }
        }
    }

    private fun toWritableArray(list: List<Map<String, Any?>>): WritableArray {
        val array = Arguments.createArray()
        for (item in list) {
            val map = Arguments.createMap()
            for ((key, value) in item) {
                when (value) {
                    null -> map.putNull(key)
                    is String -> map.putString(key, value)
                    is Boolean -> map.putBoolean(key, value)
                    is Int -> map.putInt(key, value)
                    is Long -> map.putDouble(key, value.toDouble())
                    is Double -> map.putDouble(key, value)
                }
            }
            array.pushMap(map)
        }
        return array
    }

    private fun toWritableMap(map: Map<String, Any?>): com.facebook.react.bridge.WritableMap {
        val result = Arguments.createMap()
        for ((key, value) in map) {
            when (value) {
                null -> result.putNull(key)
                is String -> result.putString(key, value)
                is Boolean -> result.putBoolean(key, value)
                is Int -> result.putInt(key, value)
                is Long -> result.putDouble(key, value.toDouble())
                is Double -> result.putDouble(key, value)
            }
        }
        return result
    }

    /** 测试连接是否可用 */
    @ReactMethod
    fun testConnection(config: ReadableMap, promise: Promise) {
        runAsync(promise) {
            val cfg = SmbConfigData.fromMap(config)
            val share = manager.execute(cfg) { it }
            val rootInfo = toWritableMap(manager.stat(share, ""))
            val result = Arguments.createMap().apply {
                putBoolean("ok", true)
                putString("share", cfg.share)
                putMap("root", rootInfo)
            }
            promise.resolve(result)
        }
    }

    @ReactMethod
    fun list(config: ReadableMap, path: String, promise: Promise) {
        runAsync(promise) {
            val cfg = SmbConfigData.fromMap(config)
            val items = manager.execute(cfg) { manager.listDir(it, path) }
            promise.resolve(toWritableArray(items))
        }
    }

    @ReactMethod
    fun stat(config: ReadableMap, path: String, promise: Promise) {
        runAsync(promise) {
            val cfg = SmbConfigData.fromMap(config)
            val info = manager.execute(cfg) { manager.stat(it, path) }
            promise.resolve(toWritableMap(info))
        }
    }

    @ReactMethod
    fun exists(config: ReadableMap, path: String, promise: Promise) {
        runAsync(promise) {
            val cfg = SmbConfigData.fromMap(config)
            val result = manager.execute(cfg) { manager.exists(it, path) }
            promise.resolve(result)
        }
    }

    @ReactMethod
    fun mkdir(config: ReadableMap, path: String, promise: Promise) {
        runAsync(promise) {
            val cfg = SmbConfigData.fromMap(config)
            manager.execute(cfg) { manager.mkdirs(it, path) }
            promise.resolve(true)
        }
    }

    @ReactMethod
    fun readText(config: ReadableMap, path: String, promise: Promise) {
        runAsync(promise) {
            val cfg = SmbConfigData.fromMap(config)
            val text = manager.execute(cfg) { manager.readText(it, path) }
            promise.resolve(text)
        }
    }

    @ReactMethod
    fun writeText(config: ReadableMap, path: String, content: String, promise: Promise) {
        runAsync(promise) {
            val cfg = SmbConfigData.fromMap(config)
            manager.execute(cfg) { manager.writeText(it, path, content) }
            promise.resolve(true)
        }
    }

    @ReactMethod
    fun uploadFile(
        config: ReadableMap,
        localPath: String,
        remotePath: String,
        taskId: String,
        promise: Promise,
    ) {
        runAsync(promise) {
            val cfg = SmbConfigData.fromMap(config)
            manager.execute(cfg) {
                manager.upload(it, localPath, remotePath) { transferred, total ->
                    emitProgress(taskId, transferred, total)
                }
            }
            promise.resolve(true)
        }
    }

    @ReactMethod
    fun downloadFile(
        config: ReadableMap,
        remotePath: String,
        localPath: String,
        taskId: String,
        promise: Promise,
    ) {
        runAsync(promise) {
            val cfg = SmbConfigData.fromMap(config)
            val size = manager.execute(cfg) {
                manager.download(it, remotePath, localPath) { transferred, total ->
                    emitProgress(taskId, transferred, total)
                }
            }
            promise.resolve(size.toDouble())
        }
    }

    @ReactMethod
    fun delete(config: ReadableMap, path: String, promise: Promise) {
        runAsync(promise) {
            val cfg = SmbConfigData.fromMap(config)
            manager.execute(cfg) { manager.delete(it, path) }
            promise.resolve(true)
        }
    }

    @ReactMethod
    fun closeConnection(promise: Promise) {
        runAsync(promise) {
            manager.close()
            promise.resolve(true)
        }
    }

    /** 启动前台备份服务（让备份切到后台也能继续） */
    @ReactMethod
    fun startBackgroundService(title: String, text: String, promise: Promise) {
        try {
            val intent = Intent(reactContext, SmbBackupService::class.java).apply {
                action = SmbBackupService.ACTION_START
                putExtra(SmbBackupService.EXTRA_TITLE, title.ifEmpty { "SMB 备份" })
                putExtra(SmbBackupService.EXTRA_TEXT, text)
            }
            androidx.core.content.ContextCompat.startForegroundService(reactContext, intent)
            promise.resolve(true)
        } catch (e: Throwable) {
            reject(promise, e)
        }
    }

    /** 更新备份通知进度 */
    @ReactMethod
    fun updateBackgroundService(
        title: String,
        text: String,
        current: Int,
        total: Int,
        promise: Promise,
    ) {
        try {
            if (!SmbBackupService.running) {
                promise.resolve(false)
                return
            }
            val intent = Intent(reactContext, SmbBackupService::class.java).apply {
                action = SmbBackupService.ACTION_UPDATE
                if (title.isNotEmpty()) {
                    putExtra(SmbBackupService.EXTRA_TITLE, title)
                }
                putExtra(SmbBackupService.EXTRA_TEXT, text)
                putExtra(SmbBackupService.EXTRA_CURRENT, current)
                putExtra(SmbBackupService.EXTRA_TOTAL, total)
            }
            reactContext.startService(intent)
            promise.resolve(true)
        } catch (e: Throwable) {
            reject(promise, e)
        }
    }

    /** 停止备份服务 */
    @ReactMethod
    fun stopBackgroundService(promise: Promise) {
        try {
            val intent = Intent(reactContext, SmbBackupService::class.java).apply {
                action = SmbBackupService.ACTION_STOP
            }
            reactContext.startService(intent)
            promise.resolve(true)
        } catch (e: Throwable) {
            reject(promise, e)
        }
    }

    /** RN 事件订阅需要的空实现 */
    @ReactMethod
    fun addListener(eventName: String) {
        // no-op
    }

    @ReactMethod
    fun removeListeners(count: Int) {
        // no-op
    }
}
