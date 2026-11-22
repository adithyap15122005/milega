package com.milega.app.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.milega.app.container
import com.milega.app.data.AdjustBody
import com.milega.app.data.ApiException
import com.milega.app.data.ListingDto
import com.milega.app.data.local.OutboxDao
import com.milega.app.data.local.PendingStockOp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/** What happened when one queued change was sent. */
sealed interface SendResult {
    data object Applied : SendResult
    /** Network trouble or server busy: keep the change and try again later (same key). */
    data object TryLater : SendResult
    /** Signed out / token expired: keep everything until the user logs in again. */
    data object AuthRequired : SendResult
    /** The server refused it (e.g. not enough stock any more). Needs the shopkeeper's decision. */
    data class Rejected(val code: String, val message: String) : SendResult
}

sealed interface DrainOutcome {
    val sent: Int
    data class Finished(override val sent: Int, val rejected: Int) : DrainOutcome
    data class RetryLater(override val sent: Int) : DrainOutcome
    data class Blocked(override val sent: Int) : DrainOutcome
}

/**
 * Sends queued stock changes one at a time, oldest first. Pure logic over [OutboxDao] and a
 * [send] function, so it is unit-tested without a network or a phone.
 */
class OutboxSyncer(private val dao: OutboxDao, private val send: suspend (PendingStockOp) -> SendResult) {

    suspend fun drain(maxOps: Int = 500): DrainOutcome {
        dao.resetInterrupted()
        var sent = 0
        var rejected = 0
        repeat(maxOps) {
            val op = dao.nextPending() ?: return DrainOutcome.Finished(sent, rejected)
            dao.setState(op.id, PendingStockOp.STATE_SYNCING, increment = 1)
            val result = try {
                send(op)
            } catch (e: CancellationException) {
                dao.setState(op.id, PendingStockOp.STATE_PENDING)
                throw e
            }
            when (result) {
                SendResult.Applied -> {
                    dao.delete(op.id)
                    sent++
                }
                SendResult.TryLater -> {
                    dao.setState(op.id, PendingStockOp.STATE_PENDING, code = "network")
                    return DrainOutcome.RetryLater(sent)
                }
                SendResult.AuthRequired -> {
                    dao.setState(op.id, PendingStockOp.STATE_PENDING, code = "auth")
                    return DrainOutcome.Blocked(sent)
                }
                is SendResult.Rejected -> {
                    dao.setState(op.id, PendingStockOp.STATE_FAILED, code = result.code, message = result.message)
                    rejected++
                }
            }
        }
        return DrainOutcome.RetryLater(sent)
    }

    companion object {
        /** Map an API failure to what the outbox should do with the change. */
        fun classify(error: Throwable): SendResult = when {
            error !is ApiException -> SendResult.TryLater
            error.status == 0 || error.status == 408 || error.status == 429 || error.status >= 500 -> SendResult.TryLater
            error.status == 401 -> SendResult.AuthRequired
            else -> SendResult.Rejected(error.code, error.message ?: error.code)
        }
    }
}

/** Projected stock for a listing after applying queued (not yet confirmed) changes in order. */
fun projectOnHand(serverOnHand: Double, ops: List<PendingStockOp>): Double {
    var value = serverOnHand
    for (op in ops) {
        if (op.state == PendingStockOp.STATE_FAILED) continue
        value = when (op.mode) {
            "add" -> value + op.quantity
            "sell" -> (value - op.quantity).coerceAtLeast(0.0)
            "set" -> op.quantity
            "out_of_stock" -> 0.0
            else -> value
        }
    }
    return value
}

class OutboxRepository(private val context: Context, private val dao: OutboxDao) {
    val all: Flow<List<PendingStockOp>> = dao.observeAll()
    val unsyncedCount: Flow<Int> = dao.observeUnsynced()
    val failedCount: Flow<Int> = dao.observeFailed()

    suspend fun enqueue(shopId: Long, listing: ListingDto, mode: String, quantity: Double, source: String = "manual"): PendingStockOp {
        val op = PendingStockOp(
            opKey = "outbox-" + UUID.randomUUID(), shopId = shopId, listingId = listing.id, listingName = listing.name,
            unitLabel = listing.unitLabel, mode = mode, quantity = quantity, source = source,
            createdAt = System.currentTimeMillis(),
        )
        val id = dao.insert(op)
        schedule()
        return op.copy(id = id)
    }

    suspend fun retry(id: Long) {
        if (dao.retry(id) > 0) schedule()
    }

    suspend fun discard(id: Long) = dao.delete(id)

    suspend fun clearAll() = dao.clear()

    fun schedule() {
        val request = OneTimeWorkRequestBuilder<OutboxSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    companion object {
        const val WORK_NAME = "outbox-sync"
    }
}

class OutboxSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = applicationContext.container
        if (!container.session.load().loggedIn) return Result.success()
        val syncer = OutboxSyncer(container.database.outbox()) { op ->
            try {
                container.api.adjust(
                    op.listingId,
                    AdjustBody(op.mode, op.quantity, op.source, clientRecordedAt = Instant.ofEpochMilli(op.createdAt).toString()),
                    idempotencyKey = op.opKey,
                )
                SendResult.Applied
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                OutboxSyncer.classify(e)
            }
        }
        return when (syncer.drain()) {
            is DrainOutcome.Finished, is DrainOutcome.Blocked -> Result.success()
            is DrainOutcome.RetryLater -> Result.retry()
        }
    }
}
