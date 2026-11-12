package com.baraa.masroof.application.dashboard

data class CommitmentsDashboardSection(
    val loansOverview: LoansOverview,
    val transactionLoanInvolvement: Map<String, Set<String>>,
    val commitmentsOverview: CommitmentsOverview,
)
