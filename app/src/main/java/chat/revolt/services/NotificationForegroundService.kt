package chat.stoat.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import chat.stoat.R
import chat.stoat.activities.MainActivity
import chat.stoat.api.STOAT_WEBSOCKET
import chat.stoat.api.StoatAPI
import chat.stoat.api.StoatHttp
import chat.stoat.api.StoatJson
import chat.stoat.api.realtime.frames.receivable.AnyFrame
import chat.stoat.api.realtime.frames.receivable.MessageFrame
import chat.stoat.api.realtime.frames.sendable.AuthorizationFrame
import chat.stoat.api.schemas.NotificationState
import chat.stoat.api.settings.NotificationSettingsProvider
import chat.stoat.api.internals.CurrentChannelState
import io.ktor.client.plugins.websocket.ws
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import logcat.LogPriority
import logcat.logcat

class NotificationForegroundService : Service() {
    companion object {
        const val FOREGROUND_NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "revolt_notification_service"
        const val ACTION_START_SERVICE = "START_SERVICE"
        const val ACTION_STOP_SERVICE = "STOP_SERVICE"

        fun start(context: Context) {
            val intent = Intent(context, NotificationForegroundService::class.java).apply {
                action = ACTION_START_SERVICE
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, NotificationForegroundService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            context.stopService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var websocketJob: Job? = null
    private lateinit var notificationHelper: NotificationHelper

    private fun shouldNotifyMessage(channelId: String, serverId: String?, isMention: Boolean): Boolean {
        return try {
            val channel = StoatAPI.channelCache[channelId]

            when {
                channel == null -> false
                channel.type == "SavedMessages" -> false
                CurrentChannelState.shouldFilterNotification(channelId) -> {
                    logcat(LogPriority.DEBUG) { "Notification filtered: message is for currently active channel $channelId (app in foreground)" }
                    false
                }
                else -> NotificationSettingsProvider.shouldNotify(channelId, serverId, isMention)
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Notification logic failed: ${e.message}" }
            isMention
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationHelper = NotificationHelper(this)
        createNotificationChannel()
        logcat(LogPriority.INFO) { "NotificationForegroundService created" }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SERVICE -> {
                startForegroundService()
                return START_STICKY
            }
            ACTION_STOP_SERVICE -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    private fun startForegroundService() {
        val notification = createForegroundNotification()
        startForeground(FOREGROUND_NOTIFICATION_ID, notification)

        logcat(LogPriority.INFO) { "Starting WebSocket connection for notifications" }
        startWebSocketConnection()
    }

    private fun startWebSocketConnection() {
        websocketJob?.cancel()

        websocketJob = serviceScope.launch {
            var retryCount = 0

            while (true) {
                try {
                    logcat(LogPriority.INFO) { "Connecting to WebSocket..." }

                    StoatHttp.ws(STOAT_WEBSOCKET) {
                        logcat(LogPriority.INFO) { "WebSocket connected successfully" }
                        retryCount = 0

                        val authFrame = AuthorizationFrame("Authenticate", StoatAPI.sessionToken)
                        send(StoatJson.encodeToString(AuthorizationFrame.serializer(), authFrame))
                        logcat(LogPriority.DEBUG) { "Sent authentication frame" }

                        incoming.consumeEach { frame ->
                            if (frame is Frame.Text) {
                                handleWebSocketFrame(frame.readText())
                            }
                        }
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR) { "WebSocket error: ${e.message}" }
                    retryCount++

                    val retryDelay = minOf(30000, 1000 * (1 shl retryCount))
                    logcat(LogPriority.INFO) { "Retrying WebSocket connection in ${retryDelay}ms (attempt $retryCount)" }
                    delay(retryDelay.toLong())
                }
            }
        }
    }

    private suspend fun handleWebSocketFrame(frameString: String) {
        try {
            val frameType = StoatJson.decodeFromString(AnyFrame.serializer(), frameString).type

            when (frameType) {
                "Message" -> {
                    val messageFrame = StoatJson.decodeFromString(MessageFrame.serializer(), frameString)
                    handleNewMessage(messageFrame)
                }
                "Ready" -> {
                    logcat(LogPriority.INFO) { "WebSocket authenticated and ready" }
                }
                "Pong" -> {
                    logcat(LogPriority.DEBUG) { "Received pong frame" }
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN) { "Failed to handle WebSocket frame: ${e.message}" }
        }
    }

    private suspend fun handleNewMessage(messageFrame: MessageFrame) {
        try {
            val channelId = messageFrame.channel ?: return
            val channel = StoatAPI.channelCache[channelId]
            val serverId = channel?.server

            if (messageFrame.author == StoatAPI.selfId) {
                return
            }

            val selfId = StoatAPI.selfId ?: return
            
            val suppressEveryoneMentions = NotificationSettingsProvider.shouldSuppressEveryoneMentions(channelId, serverId)
            val containsEveryone = messageFrame.content?.contains("@everyone") == true
            val containsHere = messageFrame.content?.contains("@here") == true
            
            if (suppressEveryoneMentions && (containsEveryone || containsHere)) {
                return
            }
            
            val hasDirectMention = messageFrame.mentions?.contains(selfId) == true
            val hasMassMention = containsEveryone || containsHere
            
        withContext(Dispatchers.Main) {
                
                var hasRoleMention = false
                if (serverId != null && messageFrame.content != null) {
                    val mentionedRoleIds = chat.stoat.internals.text.MessageProcessor.findMentionedRoleIDs(messageFrame.content)
                    if (mentionedRoleIds.isNotEmpty()) {
                        val member = StoatAPI.members.getMember(serverId, selfId)
                        val userRoles = member?.roles ?: emptyList()
                        hasRoleMention = mentionedRoleIds.any { roleId -> userRoles.contains(roleId) }
                    }
                }
                
                val isMention = hasDirectMention || hasMassMention || hasRoleMention

                if (shouldNotifyMessage(channelId, serverId, isMention)) {
                    val author = StoatAPI.userCache[messageFrame.author]
                    val server = serverId?.let { StoatAPI.serverCache[it] }

                    notificationHelper.showMessageNotification(
                        messageFrame = messageFrame,
                        author = author,
                        channel = channel,
                        server = server
                    )

                    logcat(LogPriority.DEBUG) { "Showed notification for message ${messageFrame.id}" }
                } else {
                    logcat(LogPriority.DEBUG) { "Notification filtered out for channel $channelId" }
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Error handling message notification: ${e.message}" }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Stork Notification Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Stork connected for instant notifications"
                setShowBadge(false)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createForegroundNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Stoat Notifications")
            .setContentText("Connected and listening for messages")
            .setSmallIcon(R.drawable.ic_notification_monochrome)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        websocketJob?.cancel()
        logcat(LogPriority.INFO) { "NotificationForegroundService destroyed" }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}