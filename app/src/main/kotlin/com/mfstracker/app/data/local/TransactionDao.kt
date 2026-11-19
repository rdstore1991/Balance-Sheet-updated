package com.mfstracker.app.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.mfstracker.app.data.model.TransactionType
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {

    // --- Create ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(transaction: TransactionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransactions(transactions: List<TransactionEntity>)

    // --- Read ---

    @Query("SELECT * FROM transactions ORDER BY timestampMillis DESC, updatedAtMillis DESC")
    fun observeAllTransactions(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE type = :type ORDER BY timestampMillis DESC, updatedAtMillis DESC")
    fun observeTransactionsByType(type: TransactionType): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE recordId = :recordId ORDER BY updatedAtMillis ASC")
    fun observeTransactionsByRecordId(recordId: Long): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE id = :id LIMIT 1")
    suspend fun getTransactionById(id: String): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE recordId = :recordId ORDER BY updatedAtMillis ASC")
    suspend fun getTransactionsByRecordIdOnce(recordId: Long): List<TransactionEntity>

    @Query(
        "SELECT * FROM transactions " +
            "WHERE description LIKE '%' || :query || '%' OR notes LIKE '%' || :query || '%' " +
            "ORDER BY timestampMillis DESC, updatedAtMillis DESC"
    )
    fun searchTransactions(query: String): Flow<List<TransactionEntity>>

    @Query("SELECT COALESCE(SUM(amount), 0.0) FROM transactions WHERE type = :type")
    fun observeTotalAmountByType(type: TransactionType): Flow<Double>

    @Query("SELECT COUNT(*) FROM transactions")
    suspend fun getTransactionCount(): Int

    // --- Update ---

    @Update
    suspend fun updateTransaction(transaction: TransactionEntity): Int

    @Upsert
    suspend fun upsertTransaction(transaction: TransactionEntity)

    // --- Delete ---

    @Delete
    suspend fun deleteTransaction(transaction: TransactionEntity): Int

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteTransactionById(id: String): Int

    @Query("DELETE FROM transactions WHERE recordId = :recordId")
    suspend fun deleteTransactionsByRecordId(recordId: Long): Int

    @Query("DELETE FROM transactions")
    suspend fun deleteAllTransactions()

    // --- Atomic Snapshot Sync ---

    @Transaction
    suspend fun replaceTransactionsForRecord(
        recordId: Long,
        transactions: List<TransactionEntity>
    ) {
        deleteTransactionsByRecordId(recordId)
        if (transactions.isNotEmpty()) {
            insertTransactions(transactions)
        }
    }
}
