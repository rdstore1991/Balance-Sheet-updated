package com.mfstracker.app

import android.net.Uri
import com.mfstracker.app.data.local.TrackerRecordEntity
import com.mfstracker.app.data.local.TransactionEntity
import com.mfstracker.app.data.model.TransactionType
import com.mfstracker.app.data.preferences.CurrencyFormat
import com.mfstracker.app.data.preferences.ThemeMode
import com.mfstracker.app.data.repository.TrackerRepository
import com.mfstracker.app.ui.TransactionDataState
import com.mfstracker.app.ui.TransactionViewModel
import com.mfstracker.app.util.ExportResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var repository: FakeTransactionRepository
    private lateinit var viewModel: TransactionViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = FakeTransactionRepository()
        viewModel = TransactionViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `CRUD operations update transaction state totals and selection`() = runTest {
        assertTrue(viewModel.uiState.value.dataState is TransactionDataState.Success)
        assertEquals(0, viewModel.uiState.value.totalCount)

        // Create Expense & Income
        viewModel.createTransaction(
            type = TransactionType.EXPENSE,
            description = "Office Supplies",
            amount = 1200.0,
            notes = "Stationery",
            recordId = 1L,
            dateString = "03/11/2026"
        )
        viewModel.createTransaction(
            type = TransactionType.INCOME,
            description = "Robiul",
            amount = 2500.0,
            notes = "Loan Clear",
            recordId = 1L,
            dateString = "03/11/2026"
        )

        var state = viewModel.uiState.value
        assertEquals(2, state.totalCount)
        assertEquals(1200.0, state.totalExpenses, 0.001)
        assertEquals(2500.0, state.totalIncome, 0.001)
        assertEquals(1300.0, state.netCashFlow, 0.001)

        // Read by ID
        val expenseTx = state.transactions.first { it.type == TransactionType.EXPENSE }
        viewModel.selectTransactionById(expenseTx.id)
        assertNotNull(viewModel.uiState.value.selectedTransaction)
        assertEquals("Office Supplies", viewModel.uiState.value.selectedTransaction!!.description)

        // Update
        viewModel.updateTransaction(expenseTx.copy(amount = 1500.0, notes = "Updated note"))
        state = viewModel.uiState.value
        assertEquals(1500.0, state.totalExpenses, 0.001)
        assertEquals(1000.0, state.netCashFlow, 0.001)
        assertEquals("Updated note", state.selectedTransaction!!.notes)

        // Delete by ID
        viewModel.deleteTransactionById(expenseTx.id)
        state = viewModel.uiState.value
        assertEquals(1, state.totalCount)
        assertEquals(0.0, state.totalExpenses, 0.001)
        assertEquals(2500.0, state.totalIncome, 0.001)
        assertNull(state.selectedTransaction)
    }

    @Test
    fun `search query type filter and record filter narrow filteredTransactions`() = runTest {
        viewModel.upsertTransaction(
            TransactionEntity(
                id = "tx-1",
                recordId = 1L,
                type = TransactionType.EXPENSE,
                description = "MB DP",
                amount = 2000.0,
                notes = "",
                dateString = "03/11/2026"
            )
        )
        viewModel.upsertTransaction(
            TransactionEntity(
                id = "tx-2",
                recordId = 2L,
                type = TransactionType.INCOME,
                description = "Ripon",
                amount = 500.0,
                notes = "Due Clear",
                dateString = "02/11/2026"
            )
        )

        viewModel.selectTypeFilter(TransactionType.INCOME)
        assertEquals(1, viewModel.uiState.value.filteredTransactions.size)
        assertEquals("tx-2", viewModel.uiState.value.filteredTransactions.first().id)

        viewModel.selectTypeFilter(null)
        viewModel.updateSearchQuery("due")
        assertEquals(1, viewModel.uiState.value.filteredTransactions.size)
        assertEquals("Ripon", viewModel.uiState.value.filteredTransactions.first().description)

        viewModel.clearFilters()
        viewModel.selectRecordFilter(1L)
        assertEquals(1, viewModel.uiState.value.filteredTransactions.size)
        assertEquals("tx-1", viewModel.uiState.value.filteredTransactions.first().id)
    }

    private class FakeTransactionRepository : TrackerRepository {
        private val recordsState = MutableStateFlow<List<TrackerRecordEntity>>(emptyList())
        private val transactionsState = MutableStateFlow<List<TransactionEntity>>(emptyList())
        private val darkState = MutableStateFlow<Boolean?>(null)
        private val themeModeState = MutableStateFlow(ThemeMode.SYSTEM)
        private val currencyState = MutableStateFlow(CurrencyFormat.TAKA_SYMBOL)

        override val allRecordsFlow: Flow<List<TrackerRecordEntity>> = recordsState
        override val allTransactionsFlow: Flow<List<TransactionEntity>> = transactionsState
        override val isDarkModeFlow: Flow<Boolean?> = darkState
        override val themeModeFlow: Flow<ThemeMode> = themeModeState
        override val currencyFormatFlow: Flow<CurrencyFormat> = currencyState

        override fun observeTransactionsByType(type: TransactionType): Flow<List<TransactionEntity>> =
            transactionsState.map { list -> list.filter { it.type == type } }

        override fun observeTransactionsByRecordId(recordId: Long): Flow<List<TransactionEntity>> =
            transactionsState.map { list -> list.filter { it.recordId == recordId } }

        override fun searchTransactions(query: String): Flow<List<TransactionEntity>> =
            transactionsState.map { list ->
                list.filter {
                    it.description.contains(query, ignoreCase = true) ||
                        it.notes.contains(query, ignoreCase = true)
                }
            }

        override fun observeTotalAmountByType(type: TransactionType): Flow<Double> =
            transactionsState.map { list -> list.filter { it.type == type }.sumOf { it.amount } }

        override suspend fun getTransactionById(id: String): TransactionEntity? =
            transactionsState.value.firstOrNull { it.id == id }

        override suspend fun insertTransaction(transaction: TransactionEntity) {
            transactionsState.value = transactionsState.value.filterNot { it.id == transaction.id } + transaction
        }

        override suspend fun updateTransaction(transaction: TransactionEntity): Int {
            val exists = transactionsState.value.any { it.id == transaction.id }
            if (!exists) return 0
            transactionsState.value = transactionsState.value.map {
                if (it.id == transaction.id) transaction else it
            }
            return 1
        }

        override suspend fun upsertTransaction(transaction: TransactionEntity) {
            insertTransaction(transaction)
        }

        override suspend fun deleteTransaction(transaction: TransactionEntity): Int =
            deleteTransactionById(transaction.id)

        override suspend fun deleteTransactionById(id: String): Int {
            val before = transactionsState.value.size
            transactionsState.value = transactionsState.value.filterNot { it.id == id }
            return before - transactionsState.value.size
        }

        override suspend fun getLatestRecord(): TrackerRecordEntity? = null
        override suspend fun getRecordById(id: Long): TrackerRecordEntity? = null
        override suspend fun saveRecord(record: TrackerRecordEntity): Long = 1L
        override suspend fun deleteRecord(id: Long) {}
        override suspend fun deleteAllRecords() {
            recordsState.value = emptyList()
            transactionsState.value = emptyList()
        }

        override suspend fun setDarkMode(enabled: Boolean) {}
        override suspend fun setThemeMode(mode: ThemeMode) {}
        override suspend fun setCurrencyFormat(format: CurrencyFormat) {}
        override suspend fun exportRecordToDocuments(record: TrackerRecordEntity): Result<ExportResult> =
            Result.failure(UnsupportedOperationException())
        override suspend fun exportRecordToSafUri(uri: Uri, record: TrackerRecordEntity): Result<String> =
            Result.failure(UnsupportedOperationException())
        override suspend fun exportBatchRecordsToDocuments(
            records: List<TrackerRecordEntity>,
            rangeLabel: String
        ): Result<ExportResult> = Result.failure(UnsupportedOperationException())
        override suspend fun exportBatchRecordsToSafUri(
            uri: Uri,
            records: List<TrackerRecordEntity>,
            rangeLabel: String
        ): Result<String> = Result.failure(UnsupportedOperationException())
        override suspend fun importRecordsFromSafUri(uri: Uri): Result<Int> = Result.success(0)
    }
}
