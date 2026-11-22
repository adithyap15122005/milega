package com.milega.app

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.milega.app.data.local.MilegaDatabase
import com.milega.app.data.local.PendingStockOp
import com.milega.app.data.sync.OutboxSyncer
import com.milega.app.data.sync.SendResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The real Room queries behind the offline outbox. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class OutboxDaoTest {
    private lateinit var db: MilegaDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MilegaDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun queueOrderStateAndRecovery() = runTest {
        val dao = db.outbox()
        val a = dao.insert(op(1, "sell", 1.0, "a"))
        dao.insert(op(1, "sell", 1.0, "b"))
        assertEquals("a", dao.nextPending()?.opKey)

        dao.setState(a, PendingStockOp.STATE_SYNCING, increment = 1)
        assertEquals("b", dao.nextPending()?.opKey) // a is in flight
        assertEquals(1, dao.resetInterrupted())     // crash recovery puts it back
        assertEquals("a", dao.nextPending()?.opKey)

        dao.setState(a, PendingStockOp.STATE_FAILED, code = "insufficient_stock", message = "Only 0 left")
        assertEquals(1, dao.observeFailed().first())
        assertEquals(1, dao.observeUnsynced().first())
        assertEquals(1, dao.retry(a))
        assertEquals(0, dao.retry(a)) // only failed rows can be retried
        assertEquals(2, dao.observeUnsynced().first())
    }

    @Test
    fun duplicateKeysAreRejectedByTheDatabase() = runTest {
        val dao = db.outbox()
        dao.insert(op(1, "add", 1.0, "same-key"))
        val failed = runCatching { dao.insert(op(1, "add", 1.0, "same-key")) }
        assertEquals(true, failed.isFailure)
    }

    @Test
    fun drainWithRoomEmptiesTheQueueExactlyOnce() = runTest {
        val dao = db.outbox()
        dao.insert(op(1, "add", 2.0, "k1"))
        dao.insert(op(2, "sell", 1.0, "k2"))
        val sent = mutableListOf<String>()
        OutboxSyncer(dao) { sent += it.opKey; SendResult.Applied }.drain()
        OutboxSyncer(dao) { sent += it.opKey; SendResult.Applied }.drain() // second run: nothing left
        assertEquals(listOf("k1", "k2"), sent)
        assertNull(dao.nextPending())
    }
}
