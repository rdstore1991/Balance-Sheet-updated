package com.mfstracker.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mfstracker.app.MfsTrackerApp
import com.mfstracker.app.data.local.TransactionEntity
import com.mfstracker.app.data.model.TransactionType
import com.mfstracker.app.data.repository.TrackerRepository
import com.mfstracker.app.util.NumberFormatUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID

sealed interface TransactionDataState {
    data object Loading : TransactionDataState
    data class Success(val transactions: List<TransactionEntity>) : TransactionDataState
    data class Error(val message: String) : TransactionDataState
}

data class TransactionUiState(
    val dataState: TransactionDataState = TransactionDataState.Loading,
    val transactions: List<TransactionEntity> = emptyList(),
    val searchQuery: String = "",
    val selectedType: TransactionType? = null,
    val selectedRecordId: Long? = null,
    val totalExpenses: Double = 0.0,
    val totalIncome: Double = 0.0,
    val selectedTransaction: TransactionEntity? = null,
    val isProcessing: Boolean = false,
    val statusMessage: String? = null
) {
    val totalCount: Int
        get() = transactions.size

    val netCashFlow: Double
        get() = totalIncome - totalExpenses

    val filteredTransactions: List<TransactionEntity>
        get() {
            val query = searchQuery.trim().lowercase()
            return transactions.filter { tx ->
                val matchesType = selectedType == null || tx.type == selectedType
                val matchesRecord = selectedRecordId == null || tx.recordId == selectedRecordId
                val matchesQuery = query.isEmpty() ||
                    tx.description.lowercase().contains(query) ||
                    tx.notes.lowercase().contains(query) ||
                    tx.dateString.lowercase().contains(query) ||
                    tx.type.label.lowercase().contains(query)
                matchesType && matchesRecord && matchesQuery
            }
        }
}

/**
 * ViewModel managing state and CRUD operations for individual transaction records
 * backed by Room's TransactionDao through TrackerRepository.
 */
class TransactionViewModel(
    private val repository: TrackerRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TransactionUiState())
    val uiState: StateFlow<TransactionUiState> = _uiState.asStateFlow()

    init {
        observeTransactions()
    }

    private fun observeTransactions() {
        viewModelScope.launch {
            combine(
                repository.allTransactionsFlow,
                repository.observeTotalAmountByType(TransactionType.EXPENSE),
                repository.observeTotalAmountByType(TransactionType.INCOME)
            ) { transactions, totalExpenses, totalIncome ->
                Triple(transactions, totalExpenses, totalIncome)
            }
                .catch { error ->
                    _uiState.update { current ->
                        current.copy(
                            dataState = TransactionDataState.Error(
                                error.message ?: "Failed to load transactions"
                            )
                        )
                    }
                }
                .collect { (transactions, totalExpenses, totalIncome) ->
                    _uiState.update { current ->
                        current.copy(
                            dataState = TransactionDataState.Success(transactions),
                            transactions = transactions,
                            totalExpenses = totalExpenses,
                            totalIncome = totalIncome
                        )
                    }
                }
        }
    }

    // --- Filtering & Selection ---

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun selectTypeFilter(type: TransactionType?) {
        _uiState.update { it.copy(selectedType = type) }
    }

    fun selectRecordFilter(recordId: Long?) {
        _uiState.update { it.copy(selectedRecordId = recordId) }
    }

    fun clearFilters() {
        _uiState.update {
            it.copy(
                searchQuery = "",
                selectedType = null,
                selectedRecordId = null
            )
        }
    }

    fun selectTransactionById(id: String) {
        viewModelScope.launch {
            runCatching { repository.getTransactionById(id) }
                .onSuccess { entity ->
                    _uiState.update { it.copy(selectedTransaction = entity) }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(statusMessage = "Failed to load transaction: ${error.message}")
                    }
                }
        }
    }

    fun clearSelectedTransaction() {
        _uiState.update { it.copy(selectedTransaction = null) }
    }

    fun clearStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    // --- Room DAO CRUD Operations ---

    fun createTransaction(
        type: TransactionType,
        description: String,
        amount: Double,
        notes: String = "",
        recordId: Long = 0L,
        dateString: String = NumberFormatUtils.formatDate(LocalDate.now()),
        timestampMillis: Long = System.currentTimeMillis()
    ) {
        val trimmedDescription = description.trim()
        if (trimmedDescription.isBlank() && amount <= 0.0) {
            _uiState.update {
                it.copy(statusMessage = "Enter a description or positive amount")
            }
            return
        }

        val entity = TransactionEntity(
            id = UUID.randomUUID().toString(),
            recordId = recordId,
            type = type,
            description = trimmedDescription,
            amount = amount.coerceAtLeast(0.0),
            notes = notes.trim(),
            dateString = dateString,
            timestampMillis = timestampMillis,
            updatedAtMillis = System.currentTimeMillis()
        )

        viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true) }
            runCatching { repository.insertTransaction(entity) }
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            isProcessing = false,
                            statusMessage = "Added ${type.label.lowercase()}: ${entity.description.ifBlank { "Transaction" }}"
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isProcessing = false,
                            statusMessage = "Failed to add transaction: ${error.message}"
                        )
                    }
                }
        }
    }

    fun updateTransaction(transaction: TransactionEntity) {
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true) }
            val updatedEntity = transaction.copy(
                description = transaction.description.trim(),
                notes = transaction.notes.trim(),
                amount = transaction.amount.coerceAtLeast(0.0),
                updatedAtMillis = System.currentTimeMillis()
            )
            runCatching { repository.updateTransaction(updatedEntity) }
                .onSuccess { rowsUpdated ->
                    _uiState.update {
                        it.copy(
                            isProcessing = false,
                            selectedTransaction = if (it.selectedTransaction?.id == updatedEntity.id) {
                                updatedEntity
                            } else {
                                it.selectedTransaction
                            },
                            statusMessage = if (rowsUpdated > 0) {
                                "Updated transaction"
                            } else {
                                "Transaction not found"
                            }
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isProcessing = false,
                            statusMessage = "Failed to update transaction: ${error.message}"
                        )
                    }
                }
        }
    }

    fun upsertTransaction(transaction: TransactionEntity) {
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true) }
            val prepared = transaction.copy(
                description = transaction.description.trim(),
                notes = transaction.notes.trim(),
                amount = transaction.amount.coerceAtLeast(0.0),
                updatedAtMillis = System.currentTimeMillis()
            )
            runCatching { repository.upsertTransaction(prepared) }
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            isProcessing = false,
                            selectedTransaction = prepared,
                            statusMessage = "Saved transaction"
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isProcessing = false,
                            statusMessage = "Failed to save transaction: ${error.message}"
                        )
                    }
                }
        }
    }

    fun deleteTransaction(transaction: TransactionEntity) {
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true) }
            runCatching { repository.deleteTransaction(transaction) }
                .onSuccess { deletedRows ->
                    _uiState.update { current ->
                        current.copy(
                            isProcessing = false,
                            selectedTransaction = if (current.selectedTransaction?.id == transaction.id) {
                                null
                            } else {
                                current.selectedTransaction
                            },
                            statusMessage = if (deletedRows > 0) "Deleted transaction" else "Transaction not found"
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isProcessing = false,
                            statusMessage = "Failed to delete transaction: ${error.message}"
                        )
                    }
                }
        }
    }

    fun deleteTransactionById(id: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true) }
            runCatching { repository.deleteTransactionById(id) }
                .onSuccess { deletedRows ->
                    _uiState.update { current ->
                        current.copy(
                            isProcessing = false,
                            selectedTransaction = if (current.selectedTransaction?.id == id) {
                                null
                            } else {
                                current.selectedTransaction
                            },
                            statusMessage = if (deletedRows > 0) "Deleted transaction" else "Transaction not found"
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isProcessing = false,
                            statusMessage = "Failed to delete transaction: ${error.message}"
                        )
                    }
                }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MfsTrackerApp)
                TransactionViewModel(app.repository)
            }
        }
    }
}
