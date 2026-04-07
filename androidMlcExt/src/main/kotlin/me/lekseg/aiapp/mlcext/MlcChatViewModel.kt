package me.lekseg.aiapp.mlcext

import ai.mlc.mlcllm.MLCEngine
import ai.mlc.mlcllm.OpenAIProtocol
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

internal class MlcChatViewModel(application: Application) : AndroidViewModel(application) {

    private val gson = Gson()
    private val appDir: File =
        application.getExternalFilesDir(null) ?: application.filesDir
    private val engine = MLCEngine()
    private val engineExecutor = Executors.newSingleThreadExecutor()
    private val engineDispatcher = engineExecutor.asCoroutineDispatcher()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val downloadUiLock = Any()
    @Volatile
    private var pendingAssistantStreamForUi: String = ""
    private val assistantStreamFlushRunnable = Runnable {
        val t0 = SystemClock.elapsedRealtime()
        val len = pendingAssistantStreamForUi.length
        assistantStreaming.value = pendingAssistantStreamForUi
        diagLog(
            "streamFlush done chars=$len assignMs=${SystemClock.elapsedRealtime() - t0}",
        )
    }

    val bootstrapPhase = mutableStateOf(BootstrapPhase.Idle)
    val downloadDetail = mutableStateOf(DownloadDetailState())
    val errorMessage = mutableStateOf<String?>(null)
    val chatMessages = mutableStateListOf<ChatLine>()
    val assistantStreaming = mutableStateOf("")
    val isGenerating = mutableStateOf(false)

    private var modelDir: File? = null
    private var modelLibName: String? = null

    private var throttleLastEmitMs = 0L
    private var throttleBytesAtEmit = 0L

    private val stopStreamingRequested = AtomicBoolean(false)
    private val diagSendSeq = AtomicInteger(0)

    init {
        viewModelScope.launch {
            startBootstrap()
        }
    }

    fun retryBootstrap() {
        errorMessage.value = null
        startBootstrap()
    }

    private fun startBootstrap() {
        bootstrapPhase.value = BootstrapPhase.Loading
        diagLog("bootstrap requested phase=Loading")
        thread(start = true, name = "mlc-bootstrap") {
            val bootT0 = SystemClock.elapsedRealtime()
            try {
                runBootstrap()
                diagLog("bootstrap ok totalMs=${SystemClock.elapsedRealtime() - bootT0}")
                viewModelScope.launch(Dispatchers.Main) {
                    bootstrapPhase.value = BootstrapPhase.Ready
                    diagLog("bootstrap phase=Ready (main)")
                    Toast.makeText(getApplication(), "MLC: модель готова", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                diagLog("bootstrap fail totalMs=${SystemClock.elapsedRealtime() - bootT0} err=${e.javaClass.simpleName}: ${e.message}")
                viewModelScope.launch(Dispatchers.Main) {
                    errorMessage.value = e.message ?: e.toString()
                    bootstrapPhase.value = BootstrapPhase.Failed
                }
            }
        }
    }

    private fun pushDownloadUi(state: DownloadDetailState) {
        viewModelScope.launch(Dispatchers.Main) {
            downloadDetail.value = state
        }
    }

    private fun reportFileProgress(
        stepIndex: Int,
        stepsTotal: Int,
        phaseTitle: String,
        fileName: String,
        bytesRead: Long,
        bytesTotal: Long,
        force: Boolean,
    ) {
        synchronized(downloadUiLock) {
            val now = SystemClock.elapsedRealtime()
            if (!force && now - throttleLastEmitMs < 150) return
            val dt =
                if (throttleLastEmitMs == 0L) 1L
                else (now - throttleLastEmitMs).coerceAtLeast(1L)
            val speed =
                if (throttleLastEmitMs == 0L) {
                    0L
                } else {
                    ((bytesRead - throttleBytesAtEmit) * 1000L / dt).coerceAtLeast(0L)
                }
            throttleLastEmitMs = now
            throttleBytesAtEmit = bytesRead
            pushDownloadUi(
                DownloadDetailState(
                    stepIndex = stepIndex,
                    stepsTotal = stepsTotal,
                    phaseTitle = phaseTitle,
                    fileName = fileName,
                    bytesRead = bytesRead,
                    bytesTotal = bytesTotal,
                    speedBytesPerSecond = speed,
                ),
            )
        }
    }

    private fun downloadWithUi(
        baseUrl: String,
        relativePath: String,
        dest: File,
        stepIndex: Int,
        stepsTotal: Int,
        phaseTitle: String,
    ) {
        diagLog("download step=$stepIndex/$stepsTotal phase=$phaseTitle file=$relativePath")
        if (dest.exists() && dest.length() > 0L) {
            synchronized(downloadUiLock) {
                throttleLastEmitMs = 0L
                throttleBytesAtEmit = 0L
                pushDownloadUi(
                    DownloadDetailState(
                        stepIndex = stepIndex,
                        stepsTotal = stepsTotal,
                        phaseTitle = phaseTitle,
                        fileName = relativePath,
                        bytesRead = dest.length(),
                        bytesTotal = dest.length(),
                        speedBytesPerSecond = 0L,
                    ),
                )
            }
            return
        }
        synchronized(downloadUiLock) {
            throttleLastEmitMs = 0L
            throttleBytesAtEmit = 0L
        }
        reportFileProgress(
            stepIndex, stepsTotal, phaseTitle, relativePath,
            0L, -1L, force = true,
        )
        MlcDownload.downloadToFile(baseUrl, relativePath, dest) { read, totalLen ->
            reportFileProgress(
                stepIndex, stepsTotal, phaseTitle, relativePath,
                read, totalLen, force = false,
            )
        }
        reportFileProgress(
            stepIndex, stepsTotal, phaseTitle, relativePath,
            dest.length(),
            if (dest.length() > 0L) dest.length() else -1L,
            force = true,
        )
    }

    private fun downloadFilesParallel(
        baseUrl: String,
        tasks: List<Triple<String, File, String>>,
        totalSteps: Int,
        startStepIndex: Int,
    ) {
        val n = tasks.size
        if (n == 1) {
            val (rel, dest, phase) = tasks[0]
            downloadWithUi(baseUrl, rel, dest, startStepIndex, totalSteps, phase)
            return
        }
        val pool = Executors.newFixedThreadPool(minOf(4, n))
        try {
            tasks.mapIndexed { i, (rel, dest, phase) ->
                pool.submit {
                    downloadWithUi(baseUrl, rel, dest, startStepIndex + i, totalSteps, phase)
                }
            }.forEach { it.get() }
        } finally {
            pool.shutdown()
        }
    }

    private fun runBootstrap() {
        diagLog("runBootstrap begin thread=${Thread.currentThread().name}")
        val ctx = getApplication<Application>()
        val appConfigFile = File(appDir, APP_CONFIG_NAME)
        if (!appConfigFile.exists()) {
            ctx.assets.open(APP_CONFIG_NAME).use { input ->
                appConfigFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        val appConfig = gson.fromJson(appConfigFile.readText(), AppConfig::class.java)
        val records = appConfig.modelList ?: emptyList()
        require(records.isNotEmpty()) { "model_list пуст в mlc-app-config.json" }
        val record = records.first()
        if (appConfig.modelLibs == null) appConfig.modelLibs = mutableListOf()
        appConfig.modelLibs!!.clear()
        records.forEach { appConfig.modelLibs!!.add(it.modelLib) }

        val baseUrl = MlcDownload.normalizeBaseUrl(record.modelUrl)
        val dir = File(appDir, record.modelId)
        dir.mkdirs()

        viewModelScope.launch(Dispatchers.Main) {
            bootstrapPhase.value = BootstrapPhase.Downloading
        }

        val chatFile = File(dir, CHAT_CONFIG_NAME)
        if (!isPersistedChatConfigUsable(chatFile)) {
            chatFile.delete()
        }
        downloadWithUi(
            baseUrl, CHAT_CONFIG_NAME, chatFile,
            1, 0,
            "Конфигурация чата",
        )
        patchChatConfigPreserveAllKeys(chatFile, record)
        val tokenizerFiles = readTokenizerFilesFromChatConfig(chatFile)

        val paramsFile = File(dir, PARAMS_NAME)
        downloadWithUi(
            baseUrl, PARAMS_NAME, paramsFile,
            2, 0,
            "Индекс весов (tensor-cache)",
        )
        val paramsConfig = gson.fromJson(paramsFile.readText(), ParamsConfig::class.java)

        val totalSteps = 2 + tokenizerFiles.size + paramsConfig.paramsRecords.size
        var stepIndex = 3
        if (tokenizerFiles.isNotEmpty()) {
            val tokenizerTasks = tokenizerFiles.map { name ->
                Triple(name, File(dir, name), "Токенизатор")
            }
            downloadFilesParallel(baseUrl, tokenizerTasks, totalSteps, stepIndex)
            stepIndex += tokenizerFiles.size
        }
        val weightRecords = paramsConfig.paramsRecords
        if (weightRecords.isNotEmpty()) {
            val weightTasks = weightRecords.map { pr ->
                Triple(pr.dataPath, File(dir, pr.dataPath), "Веса модели")
            }
            downloadFilesParallel(baseUrl, weightTasks, totalSteps, stepIndex)
        }

        modelDir = dir
        modelLibName = record.modelLib

        var engineFailure: Exception? = null
        val engineT0 = SystemClock.elapsedRealtime()
        diagLog("engine reload submit path=${dir.absolutePath} lib=${record.modelLib}")
        engineExecutor.submit {
            try {
                val u0 = SystemClock.elapsedRealtime()
                engine.unload()
                diagLog("engine unload ms=${SystemClock.elapsedRealtime() - u0}")
                val r0 = SystemClock.elapsedRealtime()
                engine.reload(dir.absolutePath, record.modelLib)
                diagLog("engine reload inner ms=${SystemClock.elapsedRealtime() - r0}")
            } catch (e: Exception) {
                engineFailure = e
            }
        }.get()
        diagLog("engine reload block-get totalMs=${SystemClock.elapsedRealtime() - engineT0}")
        engineFailure?.let { throw it }
    }

    fun stopGenerating() {
        stopStreamingRequested.set(true)
        diagLog("stopGenerating requested")
    }

    private fun scheduleAssistantStreamUi(text: String) {
        pendingAssistantStreamForUi = text
        mainHandler.removeCallbacks(assistantStreamFlushRunnable)
        mainHandler.post(assistantStreamFlushRunnable)
        diagLog("streamUi scheduled chars=${text.length} (from ${Thread.currentThread().name})")
    }

    private fun cancelPendingAssistantStreamFlush() {
        mainHandler.removeCallbacks(assistantStreamFlushRunnable)
    }

    private suspend fun finishGenerationUiWithAssistant(finalText: String) {
        withContext(Dispatchers.Main) {
            val t0 = SystemClock.elapsedRealtime()
            cancelPendingAssistantStreamFlush()
            chatMessages.add(ChatLine.Assistant(finalText))
            assistantStreaming.value = ""
            isGenerating.value = false
            diagLog("finishGenerationUi assistantLen=${finalText.length} mainMs=${SystemClock.elapsedRealtime() - t0}")
        }
    }

    fun sendUserMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || isGenerating.value) return
        if (bootstrapPhase.value != BootstrapPhase.Ready) return
        if (modelDir == null || modelLibName == null) return

        val sid = diagSendSeq.incrementAndGet()
        diagLog("send#$sid begin userMainChars=${trimmed.length} phase=${bootstrapPhase.value}")

        chatMessages.clear()
        chatMessages.add(ChatLine.User(trimmed))
        isGenerating.value = true
        cancelPendingAssistantStreamFlush()
        assistantStreaming.value = ""
        stopStreamingRequested.set(false)

        val singleTurnMessage = OpenAIProtocol.ChatCompletionMessage(
            role = OpenAIProtocol.ChatCompletionRole.user,
            content = OpenAIProtocol.ChatCompletionMessageContent(text = trimmed),
        )

        viewModelScope.launch {
            withContext(engineDispatcher) {
                val engT0 = SystemClock.elapsedRealtime()
                var chunkCount = 0
                var lastLogChunk = 0
                try {
                    val createT0 = SystemClock.elapsedRealtime()
                    val responses = engine.chat.completions.create(
                        messages = listOf(singleTurnMessage),
                        stream_options = OpenAIProtocol.StreamOptions(include_usage = true),
                    )
                    diagLog("send#$sid completions.create returned ms=${SystemClock.elapsedRealtime() - createT0}")
                    var streaming = ""
                    var truncated = false
                    var exitedByStop = false
                    var lastStreamUiEmitMs = 0L
                    for (res in responses) {
                        chunkCount++
                        if (chunkCount == 1) {
                            diagLog(
                                "send#$sid firstStreamChunk totalEngineMs=${SystemClock.elapsedRealtime() - engT0}",
                            )
                        }
                        if (chunkCount - lastLogChunk >= 200) {
                            diagLog(
                                "send#$sid stream chunks=$chunkCount streamingChars=${streaming.length} engineMs=${SystemClock.elapsedRealtime() - engT0}",
                            )
                            lastLogChunk = chunkCount
                        }
                        if (stopStreamingRequested.get()) {
                            exitedByStop = true
                            break
                        }
                        for (choice in res.choices) {
                            choice.delta.content?.let { c ->
                                streaming += c.asText()
                            }
                            if (choice.finish_reason == "length") {
                                truncated = true
                            }
                        }
                        val streamUiMinIntervalMs = when {
                            streaming.length > 6000 -> 400L
                            streaming.length > 2000 -> 250L
                            else -> 100L
                        }
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastStreamUiEmitMs >= streamUiMinIntervalMs) {
                            lastStreamUiEmitMs = now
                            scheduleAssistantStreamUi(streaming)
                        }
                        res.usage?.let { }
                    }
                    diagLog(
                        "send#$sid stream done chunks=$chunkCount streamingChars=${streaming.length} totalEngineMs=${SystemClock.elapsedRealtime() - engT0}",
                    )
                    stopStreamingRequested.set(false)
                    if (!(exitedByStop && streaming.isEmpty())) {
                        scheduleAssistantStreamUi(streaming)
                    }
                    if (exitedByStop) {
                        if (streaming.isNotEmpty()) {
                            streaming += " [остановлено]"
                            scheduleAssistantStreamUi(streaming)
                            finishGenerationUiWithAssistant(streaming)
                        } else {
                            withContext(Dispatchers.Main) {
                                if (chatMessages.isNotEmpty() && chatMessages.last() is ChatLine.User) {
                                    chatMessages.removeAt(chatMessages.lastIndex)
                                }
                            }
                        }
                    } else {
                        if (truncated) {
                            streaming += " [обрезано по лимиту контекста]"
                            scheduleAssistantStreamUi(streaming)
                        }
                        finishGenerationUiWithAssistant(streaming)
                    }
                } catch (e: Exception) {
                    diagLog(
                        "send#$sid error ${e.javaClass.simpleName}: ${e.message} engineMs=${SystemClock.elapsedRealtime() - engT0}",
                    )
                    withContext(Dispatchers.Main) {
                        if (chatMessages.isNotEmpty() && chatMessages.last() is ChatLine.User) {
                            chatMessages.removeAt(chatMessages.lastIndex)
                        }
                        errorMessage.value = e.message ?: e.toString()
                    }
                } finally {
                    stopStreamingRequested.set(false)
                    withContext(Dispatchers.Main) {
                        if (isGenerating.value) {
                            cancelPendingAssistantStreamFlush()
                            isGenerating.value = false
                            assistantStreaming.value = ""
                        }
                    }
                }
                diagLog("send#$sid finished totalEngineMs=${SystemClock.elapsedRealtime() - engT0}")
            }
        }
    }

    override fun onCleared() {
        val t0 = SystemClock.elapsedRealtime()
        diagLog("onCleared start")
        cancelPendingAssistantStreamFlush()
        engineExecutor.submit {
            try {
                val u0 = SystemClock.elapsedRealtime()
                engine.unload()
                diagLog("onCleared engine unload ms=${SystemClock.elapsedRealtime() - u0}")
            } catch (_: Exception) {
            }
        }
        engineExecutor.shutdown()
        diagLog("onCleared done totalMs=${SystemClock.elapsedRealtime() - t0}")
        super.onCleared()
    }

    private fun isPersistedChatConfigUsable(chatFile: File): Boolean {
        if (!chatFile.exists() || chatFile.length() == 0L) return false
        return try {
            val root = JsonParser.parseString(chatFile.readText()).asJsonObject
            when {
                root.has("vocab_size") -> true
                else -> root.getAsJsonObject("model_config")?.has("vocab_size") == true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun patchChatConfigPreserveAllKeys(chatFile: File, record: ModelRecord) {
        val root = JsonParser.parseString(chatFile.readText()).asJsonObject
        root.addProperty("model_id", record.modelId)
        root.addProperty("model_lib", record.modelLib)
        record.estimatedVramBytes?.let { root.addProperty("estimated_vram_bytes", it) }
        record.overrides?.entrySet()?.forEach { e ->
            val key = e.key
            val value = e.value
            root.add(key, value.deepCopy())
            val mc = root.getAsJsonObject("model_config")
            if (mc != null && (key == "context_window_size" || key == "prefill_chunk_size")) {
                mc.add(key, value.deepCopy())
            }
        }
        chatFile.writeText(gson.toJson(root))
    }

    private fun readTokenizerFilesFromChatConfig(chatFile: File): List<String> {
        val root = JsonParser.parseString(chatFile.readText()).asJsonObject
        val arr: JsonArray = root.getAsJsonArray("tokenizer_files") ?: return emptyList()
        return arr.map { it.asJsonPrimitive.asString }
    }

    companion object {
        private const val APP_CONFIG_NAME = "mlc-app-config.json"
        private const val CHAT_CONFIG_NAME = "mlc-chat-config.json"
        private const val PARAMS_NAME = "tensor-cache.json"
        private const val LOG_TAG = "LeksegTest"
    }

    private fun diagLog(msg: String) {
        val main = Looper.myLooper() == Looper.getMainLooper()
        Log.i(LOG_TAG, "[mlc thr=${Thread.currentThread().name} main=$main] $msg")
    }
}

internal data class DownloadDetailState(
    val stepIndex: Int = 0,
    val stepsTotal: Int = 0,
    val phaseTitle: String = "",
    val fileName: String = "",
    val bytesRead: Long = 0L,
    val bytesTotal: Long = -1L,
    val speedBytesPerSecond: Long = 0L,
)

internal enum class BootstrapPhase {
    Idle,
    Loading,
    Downloading,
    Ready,
    Failed,
}

internal sealed class ChatLine {
    data class User(val text: String) : ChatLine()
    data class Assistant(val text: String) : ChatLine()
}
