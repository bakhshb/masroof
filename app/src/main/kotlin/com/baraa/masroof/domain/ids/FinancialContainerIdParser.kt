package com.baraa.masroof.domain.ids

/**
 * Parses opaque [FinancialContainerIdFactory] ids for UI filtering.
 */
object FinancialContainerIdParser {
    fun cardLast4(containerId: String?): String? {
        if (containerId.isNullOrBlank()) return null
        if (!containerId.startsWith("card:")) return null
        val last4 = containerId.substringAfterLast(':').trim()
        return last4.takeIf { it.isNotEmpty() }
    }

    fun cardLast4FromContainers(sourceContainerId: String?, destinationContainerId: String?): String? =
        cardLast4(sourceContainerId) ?: cardLast4(destinationContainerId)

    fun cardBankId(containerId: String?): String? {
        if (containerId.isNullOrBlank() || !containerId.startsWith("card:")) return null
        val bankId = containerId.removePrefix("card:").substringBefore(':').trim()
        return bankId.takeIf { it.isNotEmpty() }
    }

    fun accountMaskedNumber(containerId: String?): String? {
        if (containerId.isNullOrBlank()) return null
        if (!containerId.startsWith("account:")) return null
        return containerId.substringAfterLast(':').trim().takeIf { it.isNotEmpty() }
    }

    /**
     * `account:<bankId>:<masked>` is bank-qualified. A suffix with no bank segment,
     * such as `account:3001`, is a legacy unqualified id.
     */
    fun accountBankId(containerId: String?): String? {
        if (containerId.isNullOrBlank() || !containerId.startsWith("account:")) return null
        val body = containerId.removePrefix("account:")
        val separator = body.indexOf(':')
        if (separator <= 0 || separator >= body.lastIndex) return null
        val bankId = body.substring(0, separator).trim()
        val masked = body.substring(separator + 1).trim()
        if (bankId.isEmpty() || masked.isEmpty()) return null
        return bankId
    }

    fun isBankQualifiedAccountId(containerId: String?): Boolean = accountBankId(containerId) != null

    fun accountContainerIdsFromContainers(
        sourceContainerId: String?,
        destinationContainerId: String?,
    ): Set<String> = buildSet {
        if (!sourceContainerId.isNullOrBlank() && sourceContainerId.startsWith("account:")) {
            add(sourceContainerId)
        }
        if (!destinationContainerId.isNullOrBlank() && destinationContainerId.startsWith("account:")) {
            add(destinationContainerId)
        }
    }
}
