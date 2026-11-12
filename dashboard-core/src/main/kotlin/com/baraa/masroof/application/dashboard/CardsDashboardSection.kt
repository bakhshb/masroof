package com.baraa.masroof.application.dashboard

data class CardsDashboardSection(
    val creditFacilities: CreditFacilitiesOverview,
    val transactionCardInvolvement: Map<String, Set<String>>,
    val transactionDebitSpendInvolvement: Map<String, Set<String>>,
)
