package com.mfstracker.app.data.repository

import android.content.Context
import android.net.Uri
import com.mfstracker.app.data.local.TrackerDao
import com.mfstracker.app.data.local.TrackerRecordEntity
import com.mfstracker.app.data.local.TransactionDao
import com.mfstracker.app.data.local.TransactionEntity
import com.mfstracker.app.data.model.TransactionType
import com.mfstracker.app.data.preferences.CurrencyFormat
import com.mfstracker.app.data.preferences.ThemeMode
import com.mfstracker.app.data.preferences.ThemePreferences
import com.mfstracker.app.util.CsvExporter
import com.mfstracker.app.util.ExportResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface TrackerRepository {
    val allRecordsFlow: Flow<List<TrackerRecordEntity>>
    val allTransactionsFlow: Flow<List<TransactionEntity>>
        get() = flowOf(emptyList())
    val isDarkModeFlow: Flow<Boolean?>
    val themeModeFlow: Flow<ThemeMode>
    val currencyFormatFlow: Flow<CurrencyFormat>

    suspend fun getLatestRecord(): TrackerRecordEntity?
    suspend fun getRecordById(id: Long): TrackerRecordEntity?
    suspend fun saveRecord(record: TrackerRecordEntity): Long
    suspend fun deleteRecord(id: Long)
    suspend fun deleteAllRecords()

    // --- Transaction CRUD ---
    fun observeTransactionsByType(type: TransactionType): Flow<List<TransactionEntity>> = flowOf(emptyList())
    fun observeTransactionsByRecordId(recordId: Long): Flow<List<TransactionEntity>> = flowOf(emptyList())
    fun searchTransactions(query: String): Flow<List<TransactionEntity>> = flowOf(emptyList())
    fun observeTotalAmountByType(type: TransactionType): Flow<Double> = flowOf(0.0)
    suspend fun getTransactionById(id: String): TransactionEntity? = null
    suspend fun insertTransaction(transaction: TransactionEntity) {}
    suspend fun updateTransaction(transaction: TransactionEntity): Int = 0
    suspend fun upsertTransaction(transaction: TransactionEntity) {}
    suspend fun deleteTransaction(transaction: TransactionEntity): Int = 0
    suspend fun deleteTransactionById(id: String): Int = 0

    suspend fun setDarkMode(enabled: Boolean)
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setCurrencyFormat(format: CurrencyFormat)

    suspend fun exportRecordToDocuments(record: TrackerRecordEntity): Result<ExportResult>
    suspend fun exportRecordToSafUri(uri: Uri, record: TrackerRecordEntity): Result<String>
    suspend fun exportBatchRecordsToDocuments(
        records: List<TrackerRecordEntity>,
        rangeLabel: String
    ): Result<ExportResult>
    suspend fun exportBatchRecordsToSafUri(
        uri: Uri,
        records: List<TrackerRecordEntity>,
        rangeLabel: String
    ): Result<String>
    suspend fun importRecordsFromSafUri(uri: Uri): Result<Int>
    suspend fun seedInitialRecordsIfNeeded(): Int = 0
}

class DefaultTrackerRepository(
    private val appContext: Context,
    private val trackerDao: TrackerDao,
    private val transactionDao: TransactionDao,
    private val themePreferences: ThemePreferences
) : TrackerRepository {

    private val seedMutex = Mutex()

    override val allRecordsFlow: Flow<List<TrackerRecordEntity>> =
        trackerDao.observeAllRecords()

    override val allTransactionsFlow: Flow<List<TransactionEntity>> =
        transactionDao.observeAllTransactions()

    override val isDarkModeFlow: Flow<Boolean?> =
        themePreferences.isDarkModeFlow

    override val themeModeFlow: Flow<ThemeMode> =
        themePreferences.themeModeFlow

    override val currencyFormatFlow: Flow<CurrencyFormat> =
        themePreferences.currencyFormatFlow

    override suspend fun getLatestRecord(): TrackerRecordEntity? =
        trackerDao.getLatestRecord()

    override suspend fun getRecordById(id: Long): TrackerRecordEntity? =
        trackerDao.getRecordById(id)

    override suspend fun saveRecord(record: TrackerRecordEntity): Long {
        val savedId = if (record.id == 0L) {
            trackerDao.insertRecord(record)
        } else {
            trackerDao.updateRecord(record)
            record.id
        }
        val txEntities = record.toTransactionEntities(savedId)
        transactionDao.replaceTransactionsForRecord(savedId, txEntities)
        return savedId
    }

    override suspend fun deleteRecord(id: Long) {
        trackerDao.deleteRecordById(id)
        transactionDao.deleteTransactionsByRecordId(id)
    }

    override suspend fun deleteAllRecords() {
        trackerDao.deleteAllRecords()
        transactionDao.deleteAllTransactions()
    }

    override fun observeTransactionsByType(type: TransactionType): Flow<List<TransactionEntity>> =
        transactionDao.observeTransactionsByType(type)

    override fun observeTransactionsByRecordId(recordId: Long): Flow<List<TransactionEntity>> =
        transactionDao.observeTransactionsByRecordId(recordId)

    override fun searchTransactions(query: String): Flow<List<TransactionEntity>> =
        transactionDao.searchTransactions(query)

    override fun observeTotalAmountByType(type: TransactionType): Flow<Double> =
        transactionDao.observeTotalAmountByType(type)

    override suspend fun getTransactionById(id: String): TransactionEntity? =
        transactionDao.getTransactionById(id)

    override suspend fun insertTransaction(transaction: TransactionEntity) {
        transactionDao.insertTransaction(transaction)
    }

    override suspend fun updateTransaction(transaction: TransactionEntity): Int =
        transactionDao.updateTransaction(transaction)

    override suspend fun upsertTransaction(transaction: TransactionEntity) {
        transactionDao.upsertTransaction(transaction)
    }

    override suspend fun deleteTransaction(transaction: TransactionEntity): Int =
        transactionDao.deleteTransaction(transaction)

    override suspend fun deleteTransactionById(id: String): Int =
        transactionDao.deleteTransactionById(id)

    override suspend fun setDarkMode(enabled: Boolean) {
        themePreferences.setDarkMode(enabled)
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        themePreferences.setThemeMode(mode)
    }

    override suspend fun setCurrencyFormat(format: CurrencyFormat) {
        themePreferences.setCurrencyFormat(format)
    }

    override suspend fun exportRecordToDocuments(record: TrackerRecordEntity): Result<ExportResult> {
        return CsvExporter.exportToPublicDocuments(appContext, record)
    }

    override suspend fun exportRecordToSafUri(
        uri: Uri,
        record: TrackerRecordEntity
    ): Result<String> {
        return CsvExporter.exportToSafUri(appContext, uri, record)
    }

    override suspend fun exportBatchRecordsToDocuments(
        records: List<TrackerRecordEntity>,
        rangeLabel: String
    ): Result<ExportResult> {
        return CsvExporter.exportBatchToPublicDocuments(appContext, records, rangeLabel)
    }

    override suspend fun exportBatchRecordsToSafUri(
        uri: Uri,
        records: List<TrackerRecordEntity>,
        rangeLabel: String
    ): Result<String> {
        return CsvExporter.exportBatchToSafUri(appContext, uri, records, rangeLabel)
    }

    override suspend fun importRecordsFromSafUri(uri: Uri): Result<Int> {
        val parseResult = CsvExporter.importCsvFromSafUri(appContext, uri)
        return parseResult.mapCatching { parsedEntities ->
            val insertedIds = trackerDao.insertRecords(parsedEntities)
            val txEntities = parsedEntities.zip(insertedIds).flatMap { (record, recordId) ->
                record.toTransactionEntities(recordId)
            }
            if (txEntities.isNotEmpty()) {
                transactionDao.insertTransactions(txEntities)
            }
            parsedEntities.size
        }
    }

    override suspend fun seedInitialRecordsIfNeeded(): Int = seedMutex.withLock {
        val alreadySeeded = themePreferences.isInitialCsvSeeded()
        val insertedCount = withContext(Dispatchers.IO) {
            var inserted = 0
            if (!alreadySeeded) {
                val csvText = runCatching {
                    appContext.assets.open(BUNDLED_LEDGER_ASSET)
                        .bufferedReader(Charsets.UTF_8)
                        .use { it.readText() }
                }.getOrNull()

                if (csvText != null) {
                    val parsed = CsvExporter.parseCsvContent(csvText)
                    if (parsed.isNotEmpty()) {
                        val existingKeys = trackerDao.getAllRecordsOnce()
                            .map { it.dateString to it.timeString }
                            .toSet()

                        val toInsert = parsed.filterNot { (it.dateString to it.timeString) in existingKeys }
                        if (toInsert.isNotEmpty()) {
                            val insertedIds = trackerDao.insertRecords(toInsert)
                            val txEntities = toInsert.zip(insertedIds).flatMap { (record, recordId) ->
                                record.toTransactionEntities(recordId)
                            }
                            if (txEntities.isNotEmpty()) {
                                transactionDao.insertTransactions(txEntities)
                            }
                        }
                        inserted = toInsert.size
                    }
                }
            }

            // Backfill transactions table after v1 -> v2 migration if records already existed
            if (transactionDao.getTransactionCount() == 0) {
                val existingRecords = trackerDao.getAllRecordsOnce()
                val backfilled = existingRecords.flatMap { record ->
                    record.toTransactionEntities(record.id)
                }
                if (backfilled.isNotEmpty()) {
                    transactionDao.insertTransactions(backfilled)
                }
            }

            inserted
        }
        if (!alreadySeeded) {
            themePreferences.setInitialCsvSeeded(true)
        }
        insertedCount
    }

    private fun TrackerRecordEntity.toTransactionEntities(recordId: Long): List<TransactionEntity> {
        return transactions
            .filter { it.description.isNotBlank() || it.amount != 0.0 || it.notes.isNotBlank() }
            .map { item ->
                TransactionEntity.fromExpenseIncomeItem(
                    item = item,
                    recordId = recordId,
                    dateString = dateString,
                    timestampMillis = timestampMillis
                )
            }
    }

    private companion object {
        const val BUNDLED_LEDGER_ASSET = "MFS_Tracker_All_Records_2026-11-08_0309_60b75e05.csv"
    }
}
