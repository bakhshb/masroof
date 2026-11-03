package com.baraa.masroof.presentation.common

/** User-facing last4 label; never shows internal placeholders like "unknown". */
fun formatCardLast4(last4: String?): String = com.baraa.masroof.application.analytics.CommitmentNames.maskedLast4(last4)
