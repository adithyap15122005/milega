package com.milega.app

import com.milega.app.data.ApiException
import com.milega.app.data.local.OutboxDao
import com.milega.app.data.local.PendingStockOp
import com.milega.app.data.sync.DrainOutcome
import com.milega.app.data.sync.OutboxSyncer
import com.milega.app.data.sync.SendResult
import com.milega.app.data.sync.projectOnHand
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory OutboxDao with the same semantics as the Room queries (those are tested in OutboxDaoTest). */
class FakeOutboxDao : OutboxDao {
    val rows = MutableStateFlow<List<PendingStockOp>>(emptyList())
    private var nextId = 1L

    override suspend fun insert(op: PendingStockOp): Long {
        val id = nextId++
        rows.value = rows.value + op.copy(id = id)
        return id
    }

    override suspend fun nextPending() = rows.value.filter { it.state == PendingStockOp.STATE_PENDING }.minByOrNull { it.id }
    override fun observeAll(): Flow<List<PendingStockOp>> = rows
    override suspend fun all() = rows.value
    override fun observeUnsynced(): Flow<Int> = rows.map { r -> r.count { it.state != PendingStockOp.STATE_FAILED } }
    override fun observeFailed(): Flow<Int> = rows.map { r -> r.count { it.state == PendingStockOp.STATE_FAILED } }

    override suspend fun setState(id: Long, state: String, increment: Int, code: String?, message: String?) {
        rows.value = rows.value.map {
            if (it.id == id) it.copy(state = state, attempts = it.attempts + increment, errorCode = code, errorMessage = message) else it
        }
    }

    override suspend fun delete(id: Long) {
        rows.value = rows.value.filterNot { it.id == id }
    }

    override suspend fun resetInterrupted(): Int {
        val n = rows.value.count { it.state == PendingStockOp.STATE_SYNCING }
        rows.value = rows.value.map { if (it.state == PendingStockOp.STATE_SYNCING) it.copy(state = PendingStockOp.STATE_PENDING) else it }
        return n
    }

    override suspend fun retry(id: Long): Int {
        val hit = rows.value.any { it.id == id && it.state == PendingStockOp.STATE_FAILED }
        if (hit) setState(id, PendingStockOp.STATE_PENDING, 0, null, null)
        return if (hit) 1 else 0
    }

    override suspend fun clear() {
        rows.value = emptyList()
    }
}

fun op(listingId: Long, mode: String, q: Double, key: String) = PendingStockOp(
    opKey = key, shopId = 1, listingId = listingId, listingName = "Tata Salt 1 kg", unitLabel = "pcs",
    mode = mode, quantity = q, createdAt = 1_700_000_000_000,
)

class OutboxSyncerTest {

    @Test
    fun sendsOldestFirstAndDeletesApplied() = runTest {
        val dao = FakeOutboxDao()
        dao.insert(op(10, "sell", 1.0, "k1"))
        dao.insert(op(10, "sell", 1.0, "k2"))
        dao.insert(op(11, "add", 5.0, "k3"))
        val sentKeys = mutableListOf<String>()
        val outcome = OutboxSyncer(dao) { sentKeys += it.opKey; SendResult.Applied }.drain()
        assertEquals(listOf("k1", "k2", "k3"), sentKeys)
        assertEquals(DrainOutcome.Finished(sent = 3, rejected = 0), outcome)
        assertTrue(dao.all().isEmpty())
    }

    @Test
    fun networkFailureKeepsTheChangeAndStopsInOrder() = runTest {
        val dao = FakeOutboxDao()
        dao.insert(op(10, "sell", 1.0, "k1"))
        dao.insert(op(10, "sell", 1.0, "k2"))
        val outcome = OutboxSyncer(dao) { SendResult.TryLater }.drain()
        assertEquals(DrainOutcome.RetryLater(0), outcome)
        val rows = dao.all()
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.state == PendingStockOp.STATE_PENDING })
        assertEquals(1, rows.first { it.opKey == "k1" }.attempts)
        assertEquals(0, rows.first { it.opKey == "k2" }.attempts) // never sent out of order
    }

    @Test
    fun retryAfterRestartReusesTheSameIdempotencyKey() = runTest {
        // Simulates: app killed while sending (row stuck in 'syncing'), then the phone restarts.
        val dao = FakeOutboxDao()
        val id = dao.insert(op(10, "add", 3.0, "outbox-abc"))
        dao.setState(id, PendingStockOp.STATE_SYNCING, 1, null, null)
        val keys = mutableListOf<String>()
        OutboxSyncer(dao) { keys += it.opKey; SendResult.Applied }.drain()
        assertEquals(listOf("outbox-abc"), keys)
        assertTrue(dao.all().isEmpty())
    }

    @Test
    fun conflictIsParkedForTheShopkeeperAndOthersContinue() = runTest {
        val dao = FakeOutboxDao()
        dao.insert(op(10, "sell", 20.0, "too-many"))
        dao.insert(op(11, "add", 1.0, "fine"))
        val outcome = OutboxSyncer(dao) {
            if (it.opKey == "too-many") SendResult.Rejected("insufficient_stock", "Only 5 in stock") else SendResult.Applied
        }.drain()
        assertEquals(DrainOutcome.Finished(sent = 1, rejected = 1), outcome)
        val left = dao.all().single()
        assertEquals("too-many", left.opKey)
        assertEquals(PendingStockOp.STATE_FAILED, left.state)
        assertEquals("insufficient_stock", left.errorCode)
        // The shopkeeper taps Retry: it goes back in the queue with the same key.
        assertEquals(1, dao.retry(left.id))
        assertEquals("too-many", dao.nextPending()?.opKey)
    }

    @Test
    fun expiredLoginBlocksWithoutLosingAnything() = runTest {
        val dao = FakeOutboxDao()
        dao.insert(op(10, "add", 1.0, "k1"))
        val outcome = OutboxSyncer(dao) { SendResult.AuthRequired }.drain()
        assertEquals(DrainOutcome.Blocked(0), outcome)
        assertEquals(PendingStockOp.STATE_PENDING, dao.all().single().state)
    }

    @Test
    fun classifiesServerErrors() {
        assertEquals(SendResult.TryLater, OutboxSyncer.classify(ApiException(0, "network", "offline")))
        assertEquals(SendResult.TryLater, OutboxSyncer.classify(ApiException(503, "server_error", "busy")))
        assertEquals(SendResult.TryLater, OutboxSyncer.classify(ApiException(429, "rate_limited", "slow down")))
        assertEquals(SendResult.AuthRequired, OutboxSyncer.classify(ApiException(401, "unauthorized", "login")))
        assertEquals(SendResult.Rejected("insufficient_stock", "Only 5"), OutboxSyncer.classify(ApiException(409, "insufficient_stock", "Only 5")))
        assertEquals(SendResult.TryLater, OutboxSyncer.classify(IllegalStateException("boom")))
    }

    @Test
    fun projectsQueuedChangesInOrder() {
        val ops = listOf(op(1, "add", 5.0, "a"), op(1, "sell", 2.0, "b"), op(1, "set", 7.0, "c"), op(1, "sell", 1.0, "d"))
        assertEquals(6.0, projectOnHand(10.0, ops), 0.0)
        assertEquals(0.0, projectOnHand(1.0, listOf(op(1, "sell", 3.0, "x"))), 0.0) // never negative on screen
        val failed = op(1, "add", 100.0, "f").copy(state = PendingStockOp.STATE_FAILED)
        assertEquals(10.0, projectOnHand(10.0, listOf(failed)), 0.0) // failed changes are not counted
    }
}
