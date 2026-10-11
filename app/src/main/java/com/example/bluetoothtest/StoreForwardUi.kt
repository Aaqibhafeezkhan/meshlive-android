package com.example.bluetoothtest

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File

/**
 * Messages UI support (2026-10-11): the unread count, the quiet new-message notification and the
 * voice-note recording rule. Everything that decides something is a pure function with a JVM test;
 * the Android parts only call them.
 */
internal object UnreadMessages {
    /** Received (inbox) messages that arrived after the user last left the Messages screen. */
    fun count(messages: List<ArchivedSummary>, seenMs: Long): Int =
        messages.count { isUnread(it, seenMs) }

    fun isUnread(message: ArchivedSummary, seenMs: Long): Boolean =
        message.direction == MessageDirection.IN && message.receivedMs > seenMs
}

/**
 * When the user last left the Messages screen, as one number in the store-and-forward directory
 * (`noBackupFilesDir/store-forward`). It is not sensitive, but living there means it stays out of
 * backups and the emergency wipe (which deletes the whole directory) removes it with the messages.
 */
internal class InboxSeenStore(private val file: File) {
    fun read(): Long = runCatching { file.readText().trim().toLong() }.getOrDefault(0L)

    fun write(ms: Long) {
        if (ms <= read()) return
        runCatching {
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(ms.toString())
            if (!temp.renameTo(file)) {
                file.writeText(ms.toString())
                temp.delete()
            }
        }
    }

    companion object {
        const val FILE_NAME = "inbox-seen.txt"

        fun forRoot(root: File) = InboxSeenStore(File(root, FILE_NAME))
    }
}

/** Whether the Messages screen is in front (same process as the call screen): no notification then. */
internal object MessagesScreenState {
    @Volatile var visible = false
}

/**
 * A voice note records from the microphone, which a call (or a live stream) already holds. Two
 * captures in one app can leave the call silent for the other person, so recording is refused while
 * any session holds the microphone.
 */
internal object VoiceNoteGate {
    /** True when a voice note must not start. */
    fun blocked(callPhase: PrivateCallPhase?, sessionHoldsMicrophone: Boolean): Boolean =
        (callPhase != null && callPhase != PrivateCallPhase.ENDED) || sessionHoldsMicrophone

    /** The running call phase, kept by the call screen for the Messages screen (same process). */
    @Volatile var callPhase: PrivateCallPhase? = null

    fun blockedNow(): Boolean = blocked(
        callPhase,
        (ActiveVideoSessionService.heldTypes and android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) != 0,
    )
}

/**
 * The new-message notification: quiet (low importance, no sound or vibration), one per app, never the
 * message text or sender. [NotificationKind.MESSAGE] keeps it off a secure lock screen entirely
 * (VISIBILITY_SECRET), or behind a generic "New message" when the user allowed names there.
 */
internal object MessageNotifications {
    const val CHANNEL_ID = "new_messages"
    const val NOTIFICATION_ID = 1003
    private const val REQUEST_CODE = 3

    /** Whether a notification should be posted: something unread, and the app is not already showing it. */
    fun shouldPost(unread: Int, messagesScreenVisible: Boolean, appInForeground: Boolean): Boolean =
        unread > 0 && !messagesScreenVisible && !appInForeground

    fun post(context: Context, unread: Int) {
        val app = context.applicationContext
        val manager = app.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, app.getString(R.string.messages_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = app.getString(R.string.messages_channel_description)
                setSound(null, null)
                enableVibration(false)
                setShowBadge(true)
            },
        )
        val open = PendingIntent.getActivity(
            app,
            REQUEST_CODE,
            Intent(app, MessagesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_meshlive)
            .setContentTitle(app.getString(R.string.messages_notification_title))
            .setContentText(app.resources.getQuantityString(R.plurals.messages_notification_text, unread, unread))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setAutoCancel(true)
            .setNumber(unread)
            .setContentIntent(open)
        val notification = NotificationPrivacy.apply(
            builder,
            app,
            CHANNEL_ID,
            NotificationKind.MESSAGE,
            R.drawable.ic_stat_meshlive,
            NotificationPrivacy.showPeerNames(app),
        ).build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
            .onFailure { Log.w("BluetoothVideo", "Message notification could not be posted", it) }
    }

    fun cancel(context: Context) {
        context.applicationContext.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }
}

/** Plain-language status of one of the user's own messages; the arguments fill the string's placeholders. */
internal data class OutgoingStatus(val stringRes: Int, val args: List<Any> = emptyList())

internal object OutgoingMessageStatus {
    /**
     * [state] is the bundle's state (null: no longer stored), [auto] the automatic-sync status and
     * attempt count when that feature is on, [damaged] whether the stored copy failed its check.
     */
    fun of(
        state: BundleState?,
        auto: Pair<OutboxStatus, Int>?,
        damaged: Boolean,
        maxAttempts: Int,
    ): OutgoingStatus = when (state) {
        BundleState.QUEUED -> when (auto?.first) {
            OutboxStatus.FAILED -> OutgoingStatus(R.string.messages_status_failed_attempts, listOf(auto.second))
            OutboxStatus.CARRIED -> OutgoingStatus(R.string.messages_status_courier)
            else -> if ((auto?.second ?: 0) > 0) {
                OutgoingStatus(R.string.messages_status_attempt, listOf(auto?.second ?: 0, maxAttempts))
            } else {
                OutgoingStatus(R.string.messages_status_waiting)
            }
        }
        BundleState.HANDED -> OutgoingStatus(R.string.messages_status_handed)
        BundleState.DELIVERED -> OutgoingStatus(R.string.messages_status_delivered)
        BundleState.FAILED -> OutgoingStatus(R.string.messages_status_damaged)
        null -> if (damaged) OutgoingStatus(R.string.messages_status_damaged) else OutgoingStatus(R.string.messages_status_expired)
        else -> OutgoingStatus(R.string.messages_status_waiting)
    }
}
