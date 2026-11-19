package com.mfstracker.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mfstracker.app.data.model.ExpenseIncomeItem
import com.mfstracker.app.data.model.TransactionType
import java.util.UUID

@Entity(
    tableName = "transactions",
    indices = [
        Index(value = ["recordId"]),
        Index(value = ["timestampMillis"])
    ]
)
data class TransactionEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val recordId: Long = 0L,
    val type: TransactionType = TransactionType.EXPENSE,
    val description: String = "",
    val amount: Double = 0.0,
    val notes: String = "",
    val dateString: String = "",
    val timestampMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    fun toExpenseIncomeItem(): ExpenseIncomeItem = ExpenseIncomeItem(
        id = id,
        type = type,
        description = description,
        amount = amount,
        notes = notes
    )

    companion object {
        fun fromExpenseIncomeItem(
            item: ExpenseIncomeItem,
            recordId: Long,
            dateString: String,
            timestampMillis: Long
        ): TransactionEntity {
            return TransactionEntity(
                id = item.id.ifBlank { UUID.randomUUID().toString() },
                recordId = recordId,
                type = item.type,
                description = item.description,
                amount = item.amount,
                notes = item.notes,
                dateString = dateString,
                timestampMillis = timestampMillis,
                updatedAtMillis = System.currentTimeMillis()
            )
        }
    }
}
