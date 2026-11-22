package com.eroid.vpn.core

import android.content.Context
import android.util.Log
import io.nekohasekai.libbox.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.File

class BoxRunner(private val context: Context) {

    companion object {
        @Volatile
        private var isSetupDone = false
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _logs = MutableSharedFlow<String>(replay = 50)
    val logs: SharedFlow<String> = _logs.asSharedFlow()

    private var commandServer: CommandServer? = null
    private var commandClient: CommandClient? = null

    @Volatile
    var isRunning: Boolean = false
        private set

    private fun ensureSetup() {
        if (!isSetupDone) {
            synchronized(BoxRunner::class.java) {
                if (!isSetupDone) {
                    try {
                        val baseDir = context.filesDir
                        val workingDir = File(baseDir, "working").apply { mkdirs() }
                        val tempDir = File(context.cacheDir, "temp").apply { mkdirs() }

                        val options = SetupOptions().apply {
                            basePath = baseDir.absolutePath
                            workingPath = workingDir.absolutePath
                            tempPath = tempDir.absolutePath
                        }
                        Libbox.setup(options)
                        isSetupDone = true
                        Log.d("BoxRunner", "Libbox.setup initialized with workingPath: ${workingDir.absolutePath}")
                    } catch (e: Exception) {
                        Log.e("BoxRunner", "Libbox.setup error", e)
                    }
                }
            }
        }
    }

    fun start(configJson: String, platformInterface: PlatformInterface): Boolean {
        stop()

        return try {
            ensureSetup()

            emitLog("[EROID] Проверка конфигурации через Libbox...")
            Libbox.checkConfig(configJson)

            val handler = object : CommandServerHandler {
                override fun serviceReload() {
                    emitLog("[SingBox] Перезагрузка сервиса...")
                }

                override fun serviceStop() {
                    emitLog("[SingBox] Сервис остановлен.")
                    isRunning = false
                }

                override fun writeDebugMessage(message: String?) {
                    message?.let {
                        emitLog(it)
                    }
                }

                override fun getSystemProxyStatus(): SystemProxyStatus? = null
                override fun setSystemProxyEnabled(enabled: Boolean) {}
                override fun connectSSHAgent(): Int = 0
                override fun triggerNativeCrash() {}
            }

            emitLog("[EROID] Инициализация ядра Sing-Box 1.14 через libbox.aar...")

            val server = Libbox.newCommandServer(handler, platformInterface)
            server.start()
            server.startOrReloadService(configJson, OverrideOptions())

            commandServer = server
            isRunning = true

            // Подключаем CommandClient для получения реальных логов работы Sing-Box в реальном времени
            scope.launch {
                try {
                    kotlinx.coroutines.delay(200)
                    val clientOptions = CommandClientOptions().apply {
                        addCommand(Libbox.CommandLog)
                        addCommand(Libbox.CommandStatus)
                        statusInterval = 2_000_000_000L // 2 seconds in nanoseconds
                    }
                    val clientHandler = object : CommandClientHandler {
                        override fun clearLogs() {}
                        override fun connected() {
                            emitLog("[EROID] Поток логов Sing-Box подключен.")
                        }
                        override fun disconnected(message: String?) {
                            emitLog("[EROID] Поток логов Sing-Box отключен: $message")
                        }
                        override fun initializeClashMode(modes: StringIterator?, currentMode: String?) {}
                        override fun setDefaultLogLevel(level: Int) {}
                        override fun updateClashMode(mode: String?) {}
                        override fun writeConnectionEvents(events: ConnectionEvents?) {}
                        override fun writeDNSQuery(query: DnsQuery?) {}
                        override fun writeGroups(groups: OutboundGroupIterator?) {}
                        override fun writeLogs(iterator: LogIterator?) {
                            if (iterator != null) {
                                while (iterator.hasNext()) {
                                    val entry = iterator.next()
                                    val msg = entry?.message
                                    if (!msg.isNullOrBlank()) {
                                        emitLog(msg)
                                    }
                                }
                            }
                        }
                        override fun writeOutbounds(outbounds: OutboundGroupItemIterator?) {}
                        override fun writeStatus(status: StatusMessage?) {}
                    }
                    val client = Libbox.newCommandClient(clientHandler, clientOptions)
                    client.connect()
                    commandClient = client
                } catch (e: Exception) {
                    Log.w("BoxRunner", "CommandClient connect failed: ${e.message}")
                }
            }

            emitLog("[EROID] Туннель Sing-Box успешно запущен!")
            true
        } catch (e: Exception) {
            emitLog("[ERROR] Сбой Libbox: ${e.message}")
            false
        }
    }

    private fun emitLog(msg: String) {
        com.eroid.vpn.service.EroidVpnService.log(msg)
        scope.launch { _logs.emit(msg) }
    }

    fun stop() {
        isRunning = false

        try {
            commandClient?.disconnect()
        } catch (_: Exception) {}
        commandClient = null

        commandServer?.let {
            try {
                it.closeService()
                it.close()
            } catch (_: Exception) {}
            commandServer = null
        }
        emitLog("[EROID] Sing-Box остановлен.")
    }
}
