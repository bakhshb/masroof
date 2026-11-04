package com.baraa.masroof.presentation.common

import com.baraa.masroof.ui.components.MasroofOutlineIcons

import androidx.compose.ui.graphics.vector.ImageVector
import com.baraa.masroof.domain.model.FinancialTransactionType

/**
 * Central icon mapping so dashboard, review, and onboarding stay visually consistent.
 *
 * For the Masroof brand mark, prefer [MasroofLogo] / [MasroofLogoMark] composables.
 */
object MasroofIcons {
    val preview: ImageVector = MasroofOutlineIcons.Eye
    val edit: ImageVector = MasroofOutlineIcons.Pencil
    val delete: ImageVector = MasroofOutlineIcons.Trash2
    val copy: ImageVector = MasroofOutlineIcons.Copy
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
    val export: ImageVector = MasroofOutlineIcons.Upload
    val importBackup: ImageVector = MasroofOutlineIcons.Download
    val theme: ImageVector = MasroofOutlineIcons.Moon
    val share: ImageVector = MasroofOutlineIcons.Share2
    val more: ImageVector = MasroofOutlineIcons.EllipsisVertical

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
}
