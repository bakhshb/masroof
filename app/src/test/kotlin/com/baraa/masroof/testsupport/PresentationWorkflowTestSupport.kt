package com.baraa.masroof.testsupport

import com.baraa.masroof.application.dashboard.DashboardPeriodWorkflow
import com.baraa.masroof.application.dashboard.DashboardRegistryWorkflow
import com.baraa.masroof.application.onboarding.OnboardingOwnershipWorkflow
import com.baraa.masroof.domain.ownership.OwnershipConfirmationService
import com.baraa.masroof.domain.repository.AccountRegistryRepository
import com.baraa.masroof.domain.repository.CardRegistryRepository
import com.baraa.masroof.domain.repository.LoanRegistryRepository
import com.baraa.masroof.domain.repository.NoOpLoanRegistryRepository
import com.baraa.masroof.domain.repository.ReviewRepository
import com.baraa.masroof.sms.time.InstantClock
import java.time.Instant
import java.time.ZoneId

internal object PresentationWorkflowTestSupport {
    fun dashboardRegistryWorkflow(
        cards: CardRegistryRepository = SettingsViewModelTestSupport.emptyCardRegistry(),
        accounts: AccountRegistryRepository = SettingsViewModelTestSupport.emptyAccountRegistry(),
    ): DashboardRegistryWorkflow =
        DashboardRegistryWorkflow(
            cardRegistryRepository = cards,
            accountRegistryRepository = accounts,
        )

    fun dashboardPeriodWorkflow(
        now: Instant = Instant.parse("2026-08-11T08:00:00Z"),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): DashboardPeriodWorkflow =
        DashboardPeriodWorkflow(
            clock = InstantClock { now },
            zoneId = zoneId,
        )

    fun onboardingOwnershipWorkflow(
        accounts: AccountRegistryRepository,
        cards: CardRegistryRepository,
        reviewRepository: ReviewRepository,
        loans: LoanRegistryRepository = NoOpLoanRegistryRepository,
    ): OnboardingOwnershipWorkflow =
        OnboardingOwnershipWorkflow(
            accountRegistryRepository = accounts,
            cardRegistryRepository = cards,
            ownershipConfirmationService = OwnershipConfirmationService(
                accountRegistry = accounts,
                cardRegistry = cards,
                loanRegistry = loans,
            ),
            reviewRepository = reviewRepository,
            // Inline, so ViewModel tests stay deterministic on their test Main dispatcher.
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )
}
