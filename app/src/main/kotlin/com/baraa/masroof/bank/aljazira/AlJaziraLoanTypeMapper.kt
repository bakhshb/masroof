package com.baraa.masroof.bank.aljazira

import com.baraa.masroof.domain.model.LoanType
import com.baraa.masroof.parsing.normalizer.containsComparison

/**
 * Maps Bank AlJazira financing SMS labels (لـ: …) to [LoanType] at parse time.
 */
object AlJaziraLoanTypeMapper {
    fun fromFinancingLabel(label: String?): LoanType? {
        val normalized = label?.trim().orEmpty()
        if (normalized.isEmpty()) return null
        return when {
            normalized.containsComparison("تمويل شخصي") || normalized.containsComparison("شخصي") ->
                LoanType.PERSONAL
            normalized.containsComparison("سيارة") || normalized.containsComparison("مركبة") || normalized.containsComparison("أوتو") ->
                LoanType.AUTO
            normalized.containsComparison("عقار") || normalized.containsComparison("مسكن") || normalized.containsComparison("رهن") ||
                normalized.containsComparison("عقاري") ->
                LoanType.MORTGAGE
            normalized.containsComparison("تمويل") -> LoanType.PERSONAL
            else -> null
        }
    }
}
