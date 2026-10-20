package com.baraa.masroof.lab.bank.interpretation

import com.baraa.masroof.lab.bank.*
import com.baraa.masroof.lab.finance.value.BankId

internal data class TransferNetwork(val kind: BankNetwork?, val source: BankId?, val destination: BankId?)

/** Resolves explicit roles against exact configured aliases. Never routes a sender or owns an asset. */
internal class TransferNetworkResolver(
    private val source: SourceText,
    private val rules: NetworkRules,
    private val observations: Map<String, List<String>>,
    private val evidence: MutableList<Evidence>,
    private val problem: (String, Blocker) -> Unit,
) {
    private var invalid = false
    private fun fail(code: String) { invalid = true; problem(code, Blocker.CONFLICTING_FACTS) }
    private fun key(value: String) = SourceText(value).text.trim().replace(Regex("[ \\t]+"), " ")
    private val owners = buildMap<String, Set<BankId>> {
        rules.bankAliases.forEach { (id, aliases) -> aliases.forEach { alias ->
            val name = key(alias)
            put(name, get(name).orEmpty() + BankId(id))
        } }
    }

    fun resolve(family: MessageFamily, local: BankId, familyPhrases: List<String>, explicitRecipient: Boolean = false): TransferNetwork {
        val transfer = family in setOf(MessageFamily.TRANSFER_IN, MessageFamily.TRANSFER_OUT)
        val reader = RoleLineReader(source, mapOf("sourceBank" to rules.sourceBankLabels,
            "destinationBank" to rules.destinationBankLabels, "bankNetwork" to rules.networkLabels), evidence)
        var unidentifiedExternal=false
        fun bank(rows: List<RoleLine>,externalRole: Boolean): BankId? {
            val unknownNames=mutableSetOf<String>()
            val ids = rows.map { row ->
                val candidates = owners[key(row.normalized)].orEmpty()
                if(candidates.size==1) candidates.single()
                else if(candidates.isEmpty() && externalRole && rules.unidentifiedExternalBankMarkers.any {source.phrase(it).containsMatchIn(row.normalized) && source.phrase(it).replace(row.normalized, "").any(Char::isLetter)} &&
                    owners.keys.none {SourceText(row.normalized).phrase(it).containsMatchIn(row.normalized)}) {
                    unidentifiedExternal=true
                    unknownNames+=key(row.normalized)
                    null // External bank identity remains unknown. Never bind its suffix to a local owned asset.
                } else {fail("NETWORK_ALIAS_NOT_UNIQUE");null}
            }.filterNotNull().distinct()
            if (ids.size > 1 || unknownNames.size>1 || (unknownNames.isNotEmpty() && ids.isNotEmpty())) fail("NETWORK_ROLE_CONFLICT")
            return ids.singleOrNull()
        }
        val sourceRows = reader.read("sourceBank").toMutableList()
        val destinationRows = reader.read("destinationBank").toMutableList()
        if (rules.standaloneDestinationBrackets || rules.standaloneSourceBrackets) {
            val lines = source.text.lineSequence().toList()
            val first = lines.indexOfFirst { it.any(Char::isLetterOrDigit) }
            val next = if (first >= 0) (first + 1 until lines.size).firstOrNull { lines[it].isNotBlank() } else null
            var offset = 0
            lines.forEachIndexed { index, line ->
                val match = Regex("^ *\\[([^\\[\\]]+)] *$").matchEntire(line)
                if (match != null) {
                    val title = first >= 0 && familyPhrases.any { source.phrase(it).containsMatchIn(lines[first]) }
                    val incoming = family == MessageFamily.TRANSFER_IN && rules.standaloneSourceBrackets
                    val outgoing = family == MessageFamily.TRANSFER_OUT && rules.standaloneDestinationBrackets
                    if ((!incoming && !outgoing) || !title || (incoming && !explicitRecipient) || (index != next && !(explicitRecipient && index > first))) fail("UNBOUND_BANK_BRACKET")
                    else {
                        val range = offset..(offset + line.length - 1)
                        evidence += source.evidence("extract.network.bracket", if (incoming) "sourceBank" else "destinationBank", range)
                        (if (incoming) sourceRows else destinationRows) += RoleLine(match.groupValues[1], source.original((offset + match.groups[1]!!.range.first)..(offset + match.groups[1]!!.range.last)), range)
                    }
                }
                offset += line.length + 1
            }
        }
        val sourceBank = bank(sourceRows,family==MessageFamily.TRANSFER_IN)
        val destinationBank = bank(destinationRows,family==MessageFamily.TRANSFER_OUT)
        val networks = linkedSetOf<BankNetwork>()
        if(unidentifiedExternal) networks+=BankNetwork.INTER_BANK
        if (transfer) {
            val title = source.text.lineSequence().firstOrNull { it.any(Char::isLetterOrDigit) }.orEmpty()
            listOf("network.intra" to BankNetwork.INTRA_BANK, "network.inter" to BankNetwork.INTER_BANK).forEach { (key, kind) ->
                observations[key].orEmpty().forEach { term -> source.phrase(term).findAll(title).forEach { match ->
                    networks += kind
                    val start = source.text.indexOf(title)
                    evidence += source.evidence("extract.network.title", "observation.$key", (start + match.range.first)..(start + match.range.last))
                } }
            }
        }
        val networkRows = reader.read("bankNetwork")
        networkRows.forEach { row ->
            val value = key(row.normalized)
            val intra = value in (observations["network.intra"].orEmpty().map(::key) + "intra_bank")
            val inter = value in (observations["network.inter"].orEmpty().map(::key) + "inter_bank")
            if (intra == inter) fail("NETWORK_MARKER_NOT_UNIQUE")
            else networks += if (intra) BankNetwork.INTRA_BANK else BankNetwork.INTER_BANK
        }
        if (!transfer) {
            if (sourceRows.isNotEmpty() || destinationRows.isNotEmpty() || networkRows.isNotEmpty()) fail("NETWORK_OUTSIDE_TRANSFER")
            return TransferNetwork(null, null, null)
        }
        if (family == MessageFamily.TRANSFER_OUT) {
            if (sourceBank != null && sourceBank != local) fail("OUTGOING_SOURCE_BANK_NOT_LOCAL")
            destinationBank?.let { networks += if (it == local) BankNetwork.INTRA_BANK else BankNetwork.INTER_BANK }
        } else {
            if (destinationBank != null && destinationBank != local) fail("INCOMING_DESTINATION_BANK_NOT_LOCAL")
            sourceBank?.let { networks += if (it == local) BankNetwork.INTRA_BANK else BankNetwork.INTER_BANK }
        }
        if (networks.size != 1) fail("NETWORK_MISSING_OR_CONFLICTING")
        return TransferNetwork(if (invalid) null else networks.singleOrNull(), sourceBank, destinationBank)
    }
}
