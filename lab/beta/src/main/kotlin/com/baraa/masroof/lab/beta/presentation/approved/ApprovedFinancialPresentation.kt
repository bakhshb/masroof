package com.baraa.masroof.lab.beta.presentation.approved

import androidx.compose.ui.graphics.vector.ImageVector
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.lab.finance.posting.FinancialType
import com.baraa.masroof.ui.components.MasroofOutlineIcons

internal fun approvedFinancialTypeLabel(type: FinancialType) = when (type) {
    FinancialType.EXPENSE -> R.string.beta_approved_txn_type_expense
    FinancialType.INCOME -> R.string.beta_approved_txn_type_income
    FinancialType.SELF_TRANSFER -> R.string.beta_approved_txn_type_self_transfer
    FinancialType.EXTERNAL_TRANSFER_IN -> R.string.beta_approved_txn_type_external_in
    FinancialType.EXTERNAL_TRANSFER_OUT -> R.string.beta_approved_txn_type_external_out
    FinancialType.CREDIT_CARD_PAYMENT -> R.string.beta_approved_txn_type_card_payment
    FinancialType.REFUND -> R.string.beta_approved_txn_type_refund
    FinancialType.CASH_WITHDRAWAL -> R.string.beta_approved_txn_type_cash_withdrawal
    FinancialType.BILL_PAYMENT -> R.string.beta_approved_txn_type_bill_payment
    FinancialType.LOAN_REPAYMENT -> R.string.beta_approved_txn_type_loan_repayment
    FinancialType.FEE -> R.string.beta_approved_txn_type_fee
    FinancialType.ADJUSTMENT -> R.string.beta_approved_txn_type_adjustment
    FinancialType.UNKNOWN -> R.string.beta_approved_txn_type_unknown
}

internal fun approvedFinancialTypeIcon(type: FinancialType): ImageVector = when (type) {
    FinancialType.INCOME -> MasroofOutlineIcons.TrendingUp
    FinancialType.REFUND -> MasroofOutlineIcons.RotateCcw
    FinancialType.EXTERNAL_TRANSFER_IN -> MasroofOutlineIcons.ArrowDownLeft
    FinancialType.EXTERNAL_TRANSFER_OUT -> MasroofOutlineIcons.ArrowUpRight
    FinancialType.CREDIT_CARD_PAYMENT -> MasroofOutlineIcons.CreditCard
    FinancialType.CASH_WITHDRAWAL -> MasroofOutlineIcons.Banknote
    FinancialType.LOAN_REPAYMENT -> MasroofOutlineIcons.Landmark
    FinancialType.SELF_TRANSFER -> MasroofOutlineIcons.ArrowLeftRight
    FinancialType.EXPENSE -> MasroofOutlineIcons.ShoppingBag
    FinancialType.BILL_PAYMENT -> MasroofOutlineIcons.ReceiptText
    FinancialType.FEE -> MasroofOutlineIcons.Receipt
    FinancialType.ADJUSTMENT -> MasroofOutlineIcons.ArrowRightLeft
    FinancialType.UNKNOWN -> MasroofOutlineIcons.Info
}
