package com.milega.app.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * One stock change the shopkeeper made that the server hasn't confirmed yet.
 *
 * Rows survive app restarts and phone reboots. [opKey] is sent as the
 * Idempotency-Key, so a retry after a dropped connection is applied exactly once
 * by the server's inventory ledger. Synced rows are deleted: the server ledger is
 * the durable receipt.
 */
@Entity(
    tableName = "pending_stock_ops",
    indices = [Index(value = ["opKey"], unique = true), Index("listingId"), Index("state")],
)
data class PendingStockOp(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val opKey: String,
    val shopId: Long,
    val listingId: Long,
    val listingName: String,
    val unitLabel: String,
    /** add | sell | set | out_of_stock */
    val mode: String,
    val quantity: Double,
    val source: String = "manual",
    /** Epoch millis when the shopkeeper made the change. */
    val createdAt: Long,
    /** pending | syncing | failed */
    val state: String = STATE_PENDING,
    val attempts: Int = 0,
    val errorCode: String? = null,
    val errorMessage: String? = null,
) {
    companion object {
        const val STATE_PENDING = "pending"
        const val STATE_SYNCING = "syncing"
        const val STATE_FAILED = "failed"
    }
}

@Dao
interface OutboxDao {
    @Insert
    suspend fun insert(op: PendingStockOp): Long

    /** Oldest unsent change first: changes reach the server in the order they were made. */
    @Query("SELECT * FROM pending_stock_ops WHERE state = 'pending' ORDER BY id LIMIT 1")
    suspend fun nextPending(): PendingStockOp?

    @Query("SELECT * FROM pending_stock_ops ORDER BY id")
    fun observeAll(): Flow<List<PendingStockOp>>

    @Query("SELECT * FROM pending_stock_ops ORDER BY id")
    suspend fun all(): List<PendingStockOp>

    @Query("SELECT COUNT(*) FROM pending_stock_ops WHERE state != 'failed'")
    fun observeUnsynced(): Flow<Int>

    @Query("SELECT COUNT(*) FROM pending_stock_ops WHERE state = 'failed'")
    fun observeFailed(): Flow<Int>

    @Query("UPDATE pending_stock_ops SET state = :state, attempts = attempts + :increment, errorCode = :code, errorMessage = :message WHERE id = :id")
    suspend fun setState(id: Long, state: String, increment: Int = 0, code: String? = null, message: String? = null)

    @Query("DELETE FROM pending_stock_ops WHERE id = :id")
    suspend fun delete(id: Long)

    /** After a crash or process death mid-send, put interrupted rows back in the queue (same opKey). */
    @Query("UPDATE pending_stock_ops SET state = 'pending' WHERE state = 'syncing'")
    suspend fun resetInterrupted(): Int

    @Query("UPDATE pending_stock_ops SET state = 'pending', errorCode = NULL, errorMessage = NULL WHERE id = :id AND state = 'failed'")
    suspend fun retry(id: Long): Int

    @Query("DELETE FROM pending_stock_ops")
    suspend fun clear()
}

@Database(entities = [PendingStockOp::class], version = 1, exportSchema = true)
abstract class MilegaDatabase : RoomDatabase() {
    abstract fun outbox(): OutboxDao

    companion object {
        fun create(context: Context): MilegaDatabase =
            Room.databaseBuilder(context, MilegaDatabase::class.java, "milega.db").build()
    }
}
