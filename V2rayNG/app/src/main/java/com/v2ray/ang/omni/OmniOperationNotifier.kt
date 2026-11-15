package com.v2ray.ang.omni

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.v2ray.ang.R
import com.v2ray.ang.enums.NotificationChannelType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.NotificationHelper
import com.v2ray.ang.util.LogUtil

fun omniOperationNotificationAllowed(preferenceEnabled: Boolean, systemEnabled: Boolean): Boolean =
    preferenceEnabled && systemEnabled

/** Optional, count-only completion notifications; disabled by default and never a foreground-service substitute. */
object OmniOperationNotifier {
    private const val PREF_ENABLED = "omni_operation_notifications_enabled"

    fun isEnabled(): Boolean = MmkvManager.decodeSettingsBool(PREF_ENABLED, false)

    fun setEnabled(enabled: Boolean): Boolean = MmkvManager.encodeSettings(PREF_ENABLED, enabled)

    fun systemEnabled(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun scanCompleted(context: Context, attempted: Int, successful: Int) {
        send(
            context,
            context.getString(R.string.omni_notification_scan_title),
            context.getString(R.string.omni_notification_scan_content, successful, attempted),
        )
    }

    fun syncCompleted(context: Context, attempted: Int, successful: Int) {
        send(
            context,
            context.getString(R.string.omni_notification_sync_title),
            context.getString(R.string.omni_notification_sync_content, successful, attempted),
        )
    }

    private fun send(context: Context, title: String, content: String) {
        try {
            if (!omniOperationNotificationAllowed(isEnabled(), systemEnabled(context))) return
            NotificationHelper.notify(NotificationChannelType.OMNI_OPERATIONS, context, title, content)
        } catch (error: RuntimeException) {
            // Android 13+ can revoke the permission between the check and notify; never fail the operation itself.
            LogUtil.w("Omni", "Operation notification was not delivered", error)
        }
    }
}
