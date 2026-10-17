package com.baraa.masroof.presentation.common

import com.baraa.masroof.ui.components.MasroofOutlineIcons

import androidx.compose.ui.graphics.vector.ImageVector
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.ReviewKind

/**
 * Central icon mapping so dashboard, review, and onboarding stay visually consistent.
 *
 * For the Masroof brand mark, prefer [MasroofLogo] / [MasroofLogoMark] composables.
 */
object MasroofIcons {
    val dashboard: ImageVector = MasroofOutlineIcons.LayoutDashboard
    val palette: ImageVector = MasroofOutlineIcons.Palette
    val sun: ImageVector = MasroofOutlineIcons.Sun
    val monitor: ImageVector = MasroofOutlineIcons.Monitor
    val backup: ImageVector = MasroofOutlineIcons.DatabaseBackup
    val logs: ImageVector = MasroofOutlineIcons.ScrollText
    val clock: ImageVector = MasroofOutlineIcons.Clock3
    val appLogo: ImageVector = MasroofOutlineIcons.Wallet
    val periodHint: ImageVector = MasroofOutlineIcons.Info
    val guidance: ImageVector = MasroofOutlineIcons.CircleAlert
    val netSpending: ImageVector = MasroofOutlineIcons.TrendingDown
    val income: ImageVector = MasroofOutlineIcons.TrendingUp
    val refunds: ImageVector = MasroofOutlineIcons.Undo2
    val netCashFlow: ImageVector = MasroofOutlineIcons.ArrowRightLeft
    val moneyMovement: ImageVector = MasroofOutlineIcons.Landmark
    val recentTransactions: ImageVector = MasroofOutlineIcons.List
    val reviewQueue: ImageVector = MasroofOutlineIcons.ShieldCheck
    val notifications: ImageVector = MasroofOutlineIcons.Bell
    val rescan: ImageVector = MasroofOutlineIcons.RefreshCw
    val retry: ImageVector = MasroofOutlineIcons.RefreshCw
    val periodPrevious: ImageVector = MasroofOutlineIcons.ChevronLeft
    val periodNext: ImageVector = MasroofOutlineIcons.ChevronRight
    val backToCurrent: ImageVector = MasroofOutlineIcons.CalendarDays

    val externalIn: ImageVector = MasroofOutlineIcons.ArrowDownLeft
    val externalOut: ImageVector = MasroofOutlineIcons.ArrowUpRight
    val cardPayment: ImageVector = MasroofOutlineIcons.CreditCard
    val cashWithdrawal: ImageVector = MasroofOutlineIcons.Banknote
    val selfTransfer: ImageVector = MasroofOutlineIcons.ArrowRightLeft

    val sms: ImageVector = MasroofOutlineIcons.MessageSquare
    val calendar: ImageVector = MasroofOutlineIcons.CalendarCheck
    val analysis: ImageVector = MasroofOutlineIcons.ChartLine
    val merchant: ImageVector = MasroofOutlineIcons.Store
    val counterparty: ImageVector = MasroofOutlineIcons.User
    val sender: ImageVector = MasroofOutlineIcons.Bell
    val pairMatch: ImageVector = MasroofOutlineIcons.Link
    val success: ImageVector = MasroofOutlineIcons.CircleCheck
    val error: ImageVector = MasroofOutlineIcons.CircleAlert
    val warning: ImageVector = MasroofOutlineIcons.TriangleAlert
    val ownership: ImageVector = MasroofOutlineIcons.ShieldCheck
    val settings: ImageVector = MasroofOutlineIcons.Settings
    val customize: ImageVector = MasroofOutlineIcons.SlidersHorizontal
    val savings: ImageVector = MasroofOutlineIcons.HandCoins
    val shopping: ImageVector = MasroofOutlineIcons.ShoppingCart
    val export: ImageVector = MasroofOutlineIcons.Upload
    val importBackup: ImageVector = MasroofOutlineIcons.Download
    val theme: ImageVector = MasroofOutlineIcons.Moon
    val share: ImageVector = MasroofOutlineIcons.Share2

    fun transactionType(type: FinancialTransactionType): ImageVector =
        when (type) {
            FinancialTransactionType.EXPENSE -> MasroofOutlineIcons.ShoppingBag
            FinancialTransactionType.INCOME -> MasroofOutlineIcons.TrendingUp
            FinancialTransactionType.SELF_TRANSFER -> MasroofOutlineIcons.ArrowLeftRight
            FinancialTransactionType.EXTERNAL_TRANSFER_IN -> MasroofOutlineIcons.ArrowDownLeft
            FinancialTransactionType.EXTERNAL_TRANSFER_OUT -> MasroofOutlineIcons.ArrowUpRight
            FinancialTransactionType.CREDIT_CARD_PAYMENT -> MasroofOutlineIcons.CreditCard
            FinancialTransactionType.REFUND -> MasroofOutlineIcons.RotateCcw
            FinancialTransactionType.CASH_WITHDRAWAL -> MasroofOutlineIcons.Banknote
            FinancialTransactionType.BILL_PAYMENT -> MasroofOutlineIcons.ReceiptText
            FinancialTransactionType.LOAN_REPAYMENT -> MasroofOutlineIcons.Landmark
            FinancialTransactionType.FEE -> MasroofOutlineIcons.Receipt
            FinancialTransactionType.ADJUSTMENT -> MasroofOutlineIcons.ArrowRightLeft
            FinancialTransactionType.UNKNOWN -> MasroofOutlineIcons.Info
        }

    fun reviewKind(kind: ReviewKind): ImageVector =
        when (kind) {
            ReviewKind.NEEDS_REVIEW -> MasroofOutlineIcons.ShieldCheck
            ReviewKind.PENDING_MATCH -> MasroofOutlineIcons.Link
        }

    fun messageFamily(family: MessageFamily?): ImageVector =
        when (family) {
            MessageFamily.PURCHASE -> MasroofOutlineIcons.ShoppingCart
            MessageFamily.TRANSFER_IN -> MasroofOutlineIcons.ArrowDownLeft
            MessageFamily.TRANSFER_OUT -> MasroofOutlineIcons.ArrowUpRight
            MessageFamily.BILL_PAYMENT,
            MessageFamily.CARD_PAYMENT,
            -> MasroofOutlineIcons.CreditCard
            MessageFamily.REFUND -> MasroofOutlineIcons.Undo2
            MessageFamily.WITHDRAWAL -> MasroofOutlineIcons.Banknote
            MessageFamily.FEE -> MasroofOutlineIcons.List
            else -> MasroofOutlineIcons.Info
        }
}
