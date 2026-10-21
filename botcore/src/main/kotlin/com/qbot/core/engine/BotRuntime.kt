package com.qbot.core.engine

import com.qbot.core.ai.AiConfig
import com.qbot.core.ai.AiMode
import com.qbot.core.ai.AiResponder
import com.qbot.core.api.OpenApi
import com.qbot.core.gateway.GatewayClient
import com.qbot.core.gateway.SimpleGatewayListener
import com.qbot.core.model.IncomingMessage
import com.qbot.core.model.MessageKind
import com.qbot.core.model.ReadyUser
import com.qbot.core.model.cleanText
import com.qbot.core.util.Logger
import com.qbot.core.util.NoopLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 日志级别。 */
enum class LogLevel { VERBOSE, DEBUG, INFO, WARN, ERROR }

/** 一条运行日志。 */
data class LogEntry(
    val timestampMillis: Long,
    val level: LogLevel,
    val tag: String,
    val text: String,
)

/** 日志出口，Android 侧实现后转发到数据库/UI。 */
interface LogSink {
    fun append(entry: LogEntry)
}

/**
 * 机器人运行时。
 *
 * 把「网关 → 规则匹配 → AI 应答 → 调用 API 回复」串成一条完整链路，
 * 这一层同样不依赖 Android，因此可以在 JVM 上直接跑集成测试。
 *
 * 消息处理刻意放在后台协程里异步执行：
 * 网关回调线程必须尽快返回，否则会堵塞后续事件读取。
 */
class BotRuntime(
    private val api: OpenApi,
    private val gateway: GatewayClient,
    private val ruleEngine: RuleEngine,
    private val scope: CoroutineScope,
    private val logSink: LogSink? = null,
    private val logger: Logger = NoopLogger,
    private val aiResponder: AiResponder? = null,
    /**
     * AI 配置提供者。
     *
     * 用 lambda 而不是直接传值，是为了让用户在维护端改完 AI 设置后
     * 无需重启机器人——每条消息进来时才读取最新配置。
     */
    private val aiConfigProvider: (() -> AiConfig?)? = null,
) : SimpleGatewayListener() {
    companion object {
        private const val TAG = "Runtime"
        private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.CHINA)
    }

    /** 机器人自身 openid，用于剥离消息里的 @ 前缀。 */
    @Volatile
    var botId: String = ""
        private set

    @Volatile
    var botName: String = ""
        private set

    private var started = false

    /** 启动运行时。会同时拉起网关连接。 */
    fun start() {
        if (started) return
        started = true
        log(LogLevel.INFO, TAG, "机器人启动中")
        gateway.start()
    }

    fun stop() {
        if (!started) return
        started = false
        log(LogLevel.INFO, TAG, "机器人停止中")
        gateway.stop()
    }

    override fun onReady(
        sessionId: String,
        botUser: ReadyUser?,
    ) {
        botUser?.let {
            botId = it.id
            botName = it.username
        }
        log(LogLevel.INFO, TAG, "已上线：${botUser?.username ?: "未知"}(${botUser?.id ?: "-"})")
    }

    override fun onMessage(message: IncomingMessage) {
        val text = message.cleanText(botId)
        log(LogLevel.DEBUG, TAG, "收到[${message.kind}] $text")

        val aiConfig = aiConfigProvider?.invoke()
        val aiEnabled = aiConfig != null && aiConfig.enabled && aiConfig.isReady
        val isAiCommand =
            text.startsWith(AiResponder.COMMAND_PREFIX, ignoreCase = true)

        // AI_ALWAYS 模式下规则引擎整体让位给 AI；
        // COMMAND_ONLY 模式下 /ai 开头的消息也应绕过规则，否则用户没法"人工指定"问 AI。
        val skipRules =
            aiEnabled &&
                (
                    aiConfig?.mode == AiMode.AI_ALWAYS ||
                        (aiConfig?.mode == AiMode.COMMAND_ONLY && isAiCommand)
                )

        val rule = if (skipRules) null else ruleEngine.match(message, botId)

        if (rule != null) {
            replyWithRule(message, rule)
            return
        }

        if (aiResponder != null && aiConfig != null && aiEnabled &&
            aiResponder.shouldHandle(message, aiConfig, ruleMatched = false)
        ) {
            replyWithAi(message, aiConfig)
            return
        }

        // 规则未命中且 AI 未启用/不处理时静默丢弃，避免对每条消息都回一句
        log(LogLevel.DEBUG, TAG, "无匹配规则，忽略")
    }

    /** 命中本地规则，按模板回复。 */
    private fun replyWithRule(
        message: IncomingMessage,
        rule: Rule,
    ) {
        val reply = RuleEngine.render(rule.reply, message, botId)
        if (reply.isBlank()) return

        scope.launch {
            runCatching {
                sendReply(message, reply)
                log(LogLevel.INFO, TAG, "命中规则「${rule.name}」→ 已回复")
            }.onFailure { t ->
                logger.e(TAG, "回复失败", t)
                log(LogLevel.ERROR, TAG, "回复失败：${t.message}")
            }
        }
    }

    /** 交给 AI 生成回复，失败时按配置的兜底话术应答。 */
    private fun replyWithAi(
        message: IncomingMessage,
        config: AiConfig,
    ) {
        scope.launch {
            runCatching {
                val answer = aiResponder!!.reply(message, config)
                sendReply(message, answer)
                // 用量日志：缓存命中不仅省钱，首 token 延迟也大幅降低，
                // 命中率长期为 0 说明 system 提示词不稳定，值得排查。
                val usage = aiResponder.lastUsage.value
                if (usage != null && usage.promptTokens > 0) {
                    val hitPercent = (usage.cacheHitRatio ?: 0f) * 100
                    log(
                        LogLevel.INFO,
                        TAG,
                        "AI 已回复（${answer.length} 字）｜prompt ${usage.promptTokens} tokens" +
                            "（缓存命中 ${usage.cacheHitTokens}，${hitPercent.toInt()}%）" +
                            "｜completion ${usage.completionTokens} tokens",
                    )
                } else {
                    log(LogLevel.INFO, TAG, "AI 已回复（${answer.length} 字）")
                }
            }.onFailure { t ->
                logger.e(TAG, "AI 回复失败", t)
                log(LogLevel.ERROR, TAG, "AI 回复失败：${t.message}")

                val fallback = config.replyOnError.trim()
                if (fallback.isNotEmpty()) {
                    runCatching { sendReply(message, fallback) }
                        .onSuccess { log(LogLevel.INFO, TAG, "已发送兜底回复") }
                        .onFailure { e -> log(LogLevel.ERROR, TAG, "兜底回复也失败：${e.message}") }
                }
            }
        }
    }

    /**
     * 回复一条消息。
     *
     * 带上原消息 id 作为被动回复，不消耗主动消息配额，
     * 且客户端会展示成"回复某条消息"的样式。
     */
    private suspend fun sendReply(
        message: IncomingMessage,
        reply: String,
    ) {
        val target = message.targetId
        if (target.isNullOrBlank()) {
            log(LogLevel.WARN, TAG, "缺少回复目标，无法回复")
            return
        }

        when (message.kind) {
            MessageKind.GROUP_AT -> api.sendGroupMessage(target, reply, replyMessageId = message.messageId)
            MessageKind.C2C -> api.sendUserMessage(target, reply, replyMessageId = message.messageId)
            MessageKind.GUILD ->
                // 频道消息沿用频道 openid 发送，此处退化为按 group 通道处理
                api.sendGroupMessage(target, reply, replyMessageId = message.messageId)

            MessageKind.DIRECT -> api.sendUserMessage(target, reply, replyMessageId = message.messageId)
        }
    }

    /** 手动向指定目标推送一条消息（供维护端"手动发言"使用）。 */
    suspend fun sendManual(
        kind: MessageKind,
        targetId: String,
        content: String,
    ) {
        runCatching {
            when (kind) {
                MessageKind.GROUP_AT, MessageKind.GUILD -> api.sendGroupMessage(targetId, content)
                MessageKind.C2C, MessageKind.DIRECT -> api.sendUserMessage(targetId, content)
            }
            log(LogLevel.INFO, TAG, "手动发送成功 → $targetId")
        }.onFailure { t ->
            log(LogLevel.ERROR, TAG, "手动发送失败：${t.message}")
        }
    }

    private fun log(
        level: LogLevel,
        tag: String,
        text: String,
    ) {
        val entry = LogEntry(System.currentTimeMillis(), level, tag, text)
        when (level) {
            LogLevel.ERROR -> logger.e(tag, text)
            LogLevel.WARN -> logger.w(tag, text)
            LogLevel.INFO -> logger.i(tag, text)
            LogLevel.DEBUG -> logger.d(tag, text)
            LogLevel.VERBOSE -> logger.v(tag, text)
        }
        logSink?.append(entry)
    }

    /** 格式化时间为 HH:mm:ss，便于日志列表展示。 */
    fun formatTime(millis: Long): String = TIME_FORMAT.format(Date(millis))
}
