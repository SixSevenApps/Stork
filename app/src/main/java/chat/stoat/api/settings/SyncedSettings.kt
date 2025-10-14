package chat.stoat.api.settings

import androidx.compose.runtime.mutableStateOf
import chat.stoat.api.StoatAPI
import chat.stoat.api.StoatJson
import chat.stoat.api.routes.sync.getKeys
import chat.stoat.api.routes.sync.setKey
import chat.stoat.api.schemas.AndroidSpecificSettings
import chat.stoat.api.schemas.MuteState
import chat.stoat.api.schemas.NotificationSettings
import chat.stoat.api.schemas.OrderingSettings
import chat.stoat.api.schemas._NotificationSettingsToParse
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import logcat.LogPriority
import logcat.asLog
import logcat.logcat

/*
 * - Note: When adding a new key -
 *  1. Add corresponding methods and fields here
 *  2. Add strings for poorly formed keys hint
 *  3. Add UI for resetting the key if it's poorly formed
 */

object SyncedSettings {
    private val _ordering = mutableStateOf(OrderingSettings())
    private val _android = mutableStateOf(
        AndroidSpecificSettings(
            theme = "None",
            colourOverrides = null,
            messageReplyStyle = "None",
            serverSelectionBehavior = "LastChannel"
        )
    )
    private val _notifications = mutableStateOf(NotificationSettings())

    val ordering: OrderingSettings
        get() = _ordering.value
    val android: AndroidSpecificSettings
        get() = _android.value
    val notifications: NotificationSettings
        get() = _notifications.value

    suspend fun fetch(apiToken: String = StoatAPI.sessionToken) {
        try {
            val settings =
                getKeys("ordering", "android", "notifications", token = apiToken)

            settings["ordering"]?.let {
                try {
                    _ordering.value = StoatJson.decodeFromString(
                        OrderingSettings.serializer(),
                        it.value
                    )
                } catch (e: Exception) {
                    LoadedSettings.poorlyFormedSettingsKeys += "ordering"
                    e.printStackTrace()
                }
            }

            settings["android"]?.let {
                try {
                    _android.value = StoatJson.decodeFromString(
                        AndroidSpecificSettings.serializer(),
                        it.value
                    )
                } catch (e: Exception) {
                    LoadedSettings.poorlyFormedSettingsKeys += "android"
                    e.printStackTrace()
                }
            }

            settings["notifications"]?.let {
                _notifications.value = parseNotificationSettings(it.value)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun parseNotificationSettings(value: String): NotificationSettings {
        return try {
            var intermediate =
                StoatJson.decodeFromString(_NotificationSettingsToParse.serializer(), value)

            intermediate = intermediate.copy(
                server = intermediate.server.filterValues { it != null }
                    .filterValues { it is JsonPrimitive }
                    .filterValues { it!!.jsonPrimitive.isString },
                channel = intermediate.channel.filterValues { it != null }
                    .filterValues { it is JsonPrimitive }
                    .filterValues { it!!.jsonPrimitive.isString }
            )

            val channelMutes = mutableMapOf<String, MuteState>()
            intermediate.channel_mutes.forEach { (channelId, element) ->
                try {
                    if (element != null) {
                        val muteState = StoatJson.decodeFromJsonElement(MuteState.serializer(), element)
                        val now = System.currentTimeMillis()
                        if (muteState.until == null || muteState.until > now) {
                            channelMutes[channelId] = muteState
                        }
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.WARN) { "Failed to parse channel mute for $channelId: ${e.message}" }
                }
            }

            val serverMutes = mutableMapOf<String, MuteState>()
            intermediate.server_mutes.forEach { (serverId, element) ->
                try {
                    if (element != null) {
                        val muteState = StoatJson.decodeFromJsonElement(MuteState.serializer(), element)
                        val now = System.currentTimeMillis()
                        if (muteState.until == null || muteState.until > now) {
                            serverMutes[serverId] = muteState
                        }
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.WARN) { "Failed to parse server mute for $serverId: ${e.message}" }
                }
            }

            val serverSettings = intermediate.server.mapValues { entry ->
                when (entry.value!!.jsonPrimitive.content) {
                    "muted" -> {
                        serverMutes[entry.key] = MuteState()
                        "mention"
                    }
                    else -> entry.value!!.jsonPrimitive.content
                }
            }

            val channelSettings = intermediate.channel.mapValues { entry ->
                when (entry.value!!.jsonPrimitive.content) {
                    "muted" -> {
                        channelMutes[entry.key] = MuteState()
                        "all"
                    }
                    else -> entry.value!!.jsonPrimitive.content
                }
            }

            NotificationSettings(
                server = serverSettings,
                channel = channelSettings,
                server_mutes = serverMutes,
                channel_mutes = channelMutes
            )
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { e.asLog() }
            LoadedSettings.poorlyFormedSettingsKeys += "notifications"
            NotificationSettings()
        }
    }

    suspend fun updateOrdering(value: OrderingSettings) {
        _ordering.value = value
        setKey("ordering", StoatJson.encodeToString(OrderingSettings.serializer(), value))
    }

    suspend fun updateAndroid(value: AndroidSpecificSettings) {
        _android.value = value
        setKey("android", StoatJson.encodeToString(AndroidSpecificSettings.serializer(), value))
    }

    suspend fun updateNotifications(value: NotificationSettings) {
        _notifications.value = value
        setKey("notifications", StoatJson.encodeToString(NotificationSettings.serializer(), value))
    }

    suspend fun resetOrdering() {
        val default = OrderingSettings()
        _ordering.value = default
        setKey("ordering", StoatJson.encodeToString(OrderingSettings.serializer(), default))
    }

    suspend fun resetAndroid() {
        val default = AndroidSpecificSettings(
            theme = "None",
            colourOverrides = null,
            messageReplyStyle = "None",
            serverSelectionBehavior = "LastChannel"
        )
        _android.value = default
        setKey("android", StoatJson.encodeToString(AndroidSpecificSettings.serializer(), default))
    }

    suspend fun resetNotifications() {
        val default = NotificationSettings()
        _notifications.value = default
        setKey(
            "notifications",
            StoatJson.encodeToString(NotificationSettings.serializer(), default)
        )
    }
}
