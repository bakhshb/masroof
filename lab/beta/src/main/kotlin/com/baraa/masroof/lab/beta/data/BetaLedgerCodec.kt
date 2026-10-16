package com.baraa.masroof.lab.beta.data

import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.classification.*
import com.baraa.masroof.lab.finance.dashboard.*
import com.baraa.masroof.lab.finance.matching.*
import com.baraa.masroof.lab.finance.posting.*
import com.baraa.masroof.lab.finance.value.*
import java.io.*
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.Base64
import java.util.Collections

/** Versioned lossless financial snapshot. Unknown/corrupt versions fail closed, never reclassify.
 * The checksum detects accidental corruption; it is not an authentication/signature mechanism.
 */
internal object BetaLedgerCodec {
    private const val VERSION = "BETA_LEDGER_1"

    fun encode(posting: StoredFinancialPosting): String {
        val bytes = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { out ->
            out.input(posting.classification.input)
            out.text(posting.classification.effectiveType.name)
            out.text(posting.classification.settlement.name)
            out.list(posting.classification.metrics.entries.sortedBy { it.key.name }) {
                out.text(it.key.name); out.decimal(it.value.amount); out.text(it.value.currency.name)
            }
            out.list(posting.classification.accountMovements) {
                out.assetId(it.account); out.text(it.bucket.name); out.money(it.amount)
            }
            val command = posting.command
            out.key(command.key); out.revision(command.revision); out.text(command.type.name)
            out.money(command.amount); out.time(command.time)
            out.nullable(command.source, { out.assetId(it) }); out.nullable(command.destination, { out.assetId(it) })
            out.list(command.evidenceIds.sorted(), { out.text(it) }); out.nullable(command.appliedFx, { out.fx(it) })
            out.list(posting.observations) {
                out.input(it.input); out.text(it.direction.name)
                out.nullable(it.reference) { reference -> out.text(reference.issuer.value); out.text(reference.value) }
            }
            out.nullable(posting.matchIdentity) {
                out.text(it.environment.name); out.text(it.kind)
                out.nullable(it.issuer) { bank -> out.text(bank.value) }; out.text(it.reference)
            }
        } }.toByteArray()
        return "$VERSION:${Base64.getEncoder().encodeToString(bytes)}:${checksum(bytes)}"
    }

    fun decode(payload: String): StoredFinancialPosting {
        val parts = payload.split(':')
        check(parts.size == 3 && parts[0] == VERSION) { "Unsupported Beta ledger payload" }
        val bytes = Base64.getDecoder().decode(parts[1])
        check(checksum(bytes) == parts[2]) { "Corrupt Beta ledger payload" }
        return DataInputStream(ByteArrayInputStream(bytes)).use { source ->
            val input = source.input()
            val type = FinancialType.valueOf(source.text())
            val settlement = Settlement.valueOf(source.text())
            val metrics = source.list { SummaryMetric.valueOf(source.text()) to SignedAmount(source.decimal(), Currency.valueOf(source.text())) }
            check(metrics.map { it.first }.toSet() == SummaryMetric.entries.toSet() && metrics.size == SummaryMetric.entries.size)
            val movements = source.list { AccountMovement(source.assetId(), FlowBucket.valueOf(source.text()), source.money()) }
            val command = PostingCommand(source.key(), source.revision(), FinancialType.valueOf(source.text()), source.money(), source.time(),
                source.nullable({ source.assetId() }), source.nullable({ source.assetId() }), immutableSet(source.list({ source.text() })), source.nullable({ source.fx() }))
            val observations = source.list {
                TransferObservation(source.input(), TransferDirection.valueOf(source.text()),
                    source.nullable { ConfirmedBankTransferReference(BankId(source.text()), source.text()) })
            }
            val identity = source.nullable {
                TransferMatchIdentity(Environment.valueOf(source.text()), source.text(), source.nullable { BankId(source.text()) }, source.text())
            }
            check(source.available() == 0) { "Trailing Beta ledger data" }
            check(command.key == input.key && command.revision == input.revision && command.type == type &&
                command.amount == input.amount && command.time == input.time && command.appliedFx == input.appliedFx &&
                command.evidenceIds == input.evidenceIds && command.source == (input.source as? FinancialEndpoint.Owned)?.id &&
                command.destination == (input.destination as? FinancialEndpoint.Owned)?.id) { "Inconsistent Beta ledger snapshot" }
            check((identity == null) == observations.isEmpty())
            check(metrics.all { it.second.currency == input.amount.currency } && movements.all { it.amount.currency == input.amount.currency })
            if (identity != null) {
                check(identity.environment == command.key.environment && identity.reference.isNotBlank())
                check(when (identity.kind) {
                    "BANK_REFERENCE" -> identity.issuer != null && observations.all {
                        it.reference == ConfirmedBankTransferReference(requireNotNull(identity.issuer), identity.reference)
                    }
                    "USER_PAIR" -> identity.issuer == null
                    else -> false
                }) { "Invalid Beta transfer proof" }
                check(observations.size == 2 && observations.map { it.direction }.toSet() == TransferDirection.entries.toSet() &&
                    observations.map { it.input.key }.distinct().size == 2 && observations.all { it.input.key.environment == command.key.environment })
            }
            StoredFinancialPosting(FinancialClassification.Classified(input, type, settlement,
                Collections.unmodifiableMap(LinkedHashMap(metrics.toMap())), Collections.unmodifiableList(movements)),
                command, Collections.unmodifiableList(observations), identity)
        }
    }

    private fun DataOutputStream.input(value: FinancialClassificationInput) {
        key(value.key); revision(value.revision); text(value.storedType.name); money(value.amount); time(value.time)
        endpoint(value.source); endpoint(value.destination)
        list(value.assets.entries.sortedWith(compareBy({ it.key.environment.name }, { it.key.bank.value }, { it.key.localId }))) {
            assetId(it.key); val asset = it.value; assetId(asset.id); text(asset.kind.name)
            nullable(asset.last4) { text(it) }; nullable(asset.parentId) { assetId(it) }; nullable(asset.creditCardRole) { role -> text(role.name) }
        }
        list(value.evidenceIds.sorted()) { text(it) }; writeBoolean(value.validatedParse); text(value.authority.name)
        list(value.unresolved.sortedBy { it.name }) { text(it.name) }
        nullable(value.financingLoan) { assetId(it) }; nullable(value.appliedFx) { fx(it) }; nullable(value.incomeAttribution) { text(it.name) }
    }

    private fun DataInputStream.input(): FinancialClassificationInput {
        val key = key(); val revision = revision(); val type = FinancialType.valueOf(text()); val money = money(); val time = time()
        val source = endpoint(); val destination = endpoint()
        val entries = list { assetId() to OwnedAsset(assetId(), AssetKind.valueOf(text()), nullable { text() }, nullable { assetId() }, nullable { CreditCardRole.valueOf(text()) }) }
        check(entries.map { it.first }.distinct().size == entries.size)
        return FinancialClassificationInput(key, revision, type, money, time, source, destination,
            Collections.unmodifiableMap(LinkedHashMap(entries.toMap())), immutableSet(list { text() }), readBoolean(),
            CorrectionAuthority.valueOf(text()), immutableSet(list { ReviewReason.valueOf(text()) }), nullable { assetId() }, nullable { fx() },
            nullable { IncomeAttribution.valueOf(text()) })
    }

    private fun DataOutputStream.endpoint(value: FinancialEndpoint) = when (value) {
        is FinancialEndpoint.Owned -> { text("OWNED"); assetId(value.id) }
        FinancialEndpoint.External -> text("EXTERNAL")
        FinancialEndpoint.Unresolved -> text("UNRESOLVED")
    }
    private fun DataInputStream.endpoint(): FinancialEndpoint = when (text()) {
        "OWNED" -> FinancialEndpoint.Owned(assetId()); "EXTERNAL" -> FinancialEndpoint.External
        "UNRESOLVED" -> FinancialEndpoint.Unresolved; else -> error("Unknown Beta financial endpoint")
    }
    private fun DataOutputStream.key(value: PostingKey) { text(value.environment.name); text(value.transactionId) }
    private fun DataInputStream.key() = PostingKey(Environment.valueOf(text()), text()).also { check(it.environment == Environment.BETA) }
    private fun DataOutputStream.revision(value: PostingRevision) { writeLong(value.correction); writeLong(value.interpretation) }
    private fun DataInputStream.revision() = PostingRevision(readLong(), readLong())
    private fun DataOutputStream.assetId(value: AssetId) { text(value.environment.name); text(value.bank.value); text(value.localId) }
    private fun DataInputStream.assetId() = AssetId(Environment.valueOf(text()), BankId(text()), text()).also { check(it.environment == Environment.BETA) }
    private fun DataOutputStream.money(value: Money) { decimal(value.amount); text(value.currency.name) }
    private fun DataInputStream.money() = Money.of(decimal(), Currency.valueOf(text()))
    private fun DataOutputStream.time(value: TransactionTime) { text(value.instant.toString()); text(value.zone.id) }
    private fun DataInputStream.time() = TransactionTime(Instant.parse(text()), ZoneId.of(text()))
    private fun DataOutputStream.fx(value: AppliedFx) { decimal(value.rateToSar); text(value.source) }
    private fun DataInputStream.fx() = AppliedFx(decimal(), text())
    private fun DataOutputStream.decimal(value: BigDecimal) = text(value.toString())
    private fun DataInputStream.decimal() = BigDecimal(text())
    private fun DataOutputStream.text(value: String) { val bytes = value.toByteArray(Charsets.UTF_8); writeInt(bytes.size); write(bytes) }
    private fun DataInputStream.text(): String {
        val size = readInt(); check(size >= 0 && size <= available()) { "Invalid Beta ledger field length" }
        val bytes = ByteArray(size); readFully(bytes)
        return bytes.toString(Charsets.UTF_8).also { check(it.toByteArray(Charsets.UTF_8).contentEquals(bytes)) }
    }
    private fun <T> DataOutputStream.nullable(value: T?, write: (T) -> Unit) { writeBoolean(value != null); if (value != null) write(value) }
    private fun <T> DataInputStream.nullable(read: () -> T): T? = if (readBoolean()) read() else null
    private fun <T> DataOutputStream.list(values: Collection<T>, write: (T) -> Unit) { writeInt(values.size); values.forEach(write) }
    private fun <T> DataInputStream.list(read: () -> T): List<T> {
        val count = readInt(); check(count >= 0 && count <= available()) { "Invalid Beta ledger collection length" }
        return List(count) { read() }
    }
    private fun <T> immutableSet(values: List<T>): Set<T> {
        check(values.distinct().size == values.size)
        return Collections.unmodifiableSet(LinkedHashSet(values))
    }
    private fun checksum(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
