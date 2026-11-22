package com.milega.app.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.milega.app.container
import com.milega.app.data.push.Push

/**
 * Fallback delivery: checks for new notifications every 15 minutes (Android's minimum)
 * when push isn't configured or a push was missed. Shares the de-duplication guard with
 * [com.milega.app.data.push.MilegaMessagingService], so nothing is shown twice.
 */
class NotificationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = applicationContext.container
        val session = container.session.load()
        if (!session.loggedIn) return Result.success()
        val unread = try {
            container.api.notifications(unreadOnly = true).notifications
        } catch (e: Exception) {
            return Result.retry()
        }
        for (n in unread.sortedBy { it.id }.takeLast(5)) {
            if (container.session.markNotificationShown(n.id)) {
                Push.show(applicationContext, n.id, n.title, n.body, n.kind)
            }
        }
        // Keep the outbox moving even if the app hasn't been opened for a while.
        container.outbox.schedule()
        return Result.success()
    }
}
