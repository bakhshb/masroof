package com.baraa.masroof.bank.aljazira.extraction

import com.baraa.masroof.parsing.normalizer.comparisonRegex
import com.baraa.masroof.parsing.model.NormalizedSms

data class BillerExtraction(
    val biller: String? = null,
    val billerCode: String? = null,
)

class BillerExtractor {
    fun extract(sms: NormalizedSms): BillerExtraction {
        val comparison = sms.comparisonBody
        val billerMatch = BILLER.find(comparison)
        val biller = billerMatch?.groups?.get(1)?.range?.let { range ->
            sms.normalizedSlice(range).trim()
        }
        val codeMatch = BILLER_CODE.find(comparison)
        val code = codeMatch?.groupValues?.getOrNull(1)
        return BillerExtraction(biller = biller?.takeIf { it.isNotBlank() }, billerCode = code)
    }

    companion object {
        private val BILLER = comparisonRegex("""المفوتر\s*:\s*([^\n]+)""")
        private val BILLER_CODE = comparisonRegex("""رمز\s*المفوتر\s*:\s*([^\n]+)""")
    }
}
