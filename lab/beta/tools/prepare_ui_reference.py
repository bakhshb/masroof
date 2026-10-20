"""Prepare an isolated, pinned production capture harness without editing the working app.

Usage: python3 lab/beta/tools/prepare_ui_reference.py /tmp/new-reference
Then build :app:assembleDebug :app:assembleDebugAndroidTest in that directory and run
ApprovedFidelityMatrixTest with capturePages=transactionDetail,reviewDetail,reviewIgnored,reviewOwnership,reviewTransfer,reviewBusy on the isolated emulator.
"""
from pathlib import Path
import hashlib
import json
import subprocess
import sys

if len(sys.argv) != 2:
    raise SystemExit(__doc__)

root = Path(__file__).resolve().parents[3]
destination = Path(sys.argv[1]).resolve()
if destination == root or root in destination.parents:
    raise SystemExit('The reference must be outside the working repository.')
destination.mkdir(parents=True, exist_ok=False)
archive = subprocess.run(['git', 'archive', '0d86c5d3'], cwd=root, check=True, capture_output=True)
subprocess.run(['tar', '-x', '-C', str(destination)], input=archive.stdout, check=True)
source = 'app/src/main/kotlin/com/baraa/masroof/presentation/dashboard/TransactionDetailScreen.kt'
test = destination / 'app/src/androidTest/kotlin/com/baraa/masroof/instrumentation/ApprovedFidelityMatrixTest.kt'
text = test.read_text()
needle = '            "transactions" -> TransactionListScreen('
assert needle in text
text = text.replace('"home" -> 0; "transactions" -> 1;', '"home" -> 0; "transactions", "transactionDetail" -> 1;')
text = text.replace(needle, '''            "transactionDetail" -> TransactionDetailScreen(
                transaction=financial.allTransactions.single {it.id=="ref-11"},
                smsEvidence=listOf(TransactionSmsEvidenceUi("شراء عبر الإنترنت\\nبطاقة: 8842\\nمبلغ: SAR 600.00\\nلدى: NOON","ALJAZIRA")),
                smsLoading=false,reclassifying=false,reclassifySuccess=false,ignoring=false,error=null,
                onBack={},onReclassify={},onIgnore={},ownedCards=financial.ownedCards)
''' + needle)
review_source = 'app/src/main/kotlin/com/baraa/masroof/presentation/review/ReviewScreen.kt'
review_file = destination / review_source
review_original = review_file.read_bytes()
# Test-only entry point for a file-private renderer. All original source bytes stay intact.
review_file.write_text(review_original.decode() + """

@Composable
internal fun ReviewDetailCaptureBridge(detail: ReviewDetailUi, resolving: Boolean = false) {
    ReviewDetailScreen(detail, resolving, null, null, null, onBack = {}, onResolveType = {},
        onResolveExternal = {}, onResolvePair = {}, onDismissNonFinancial = {}, onSelectBank = {},
        onConfirmOwnershipCardOwned = {}, onMarkOwnershipCardExternal = {}, onRestoreIgnored = {})
}
""")
assert review_file.read_bytes().startswith(review_original)
text = text.replace('"transactions", "transactionDetail" -> 1;', '"transactions", "transactionDetail" -> 1; in setOf("reviewDetail","reviewIgnored","reviewOwnership","reviewTransfer","reviewBusy") -> 2;')
text = text.replace('            "transactions" -> TransactionListScreen(', '''            in setOf("reviewDetail","reviewIgnored","reviewOwnership","reviewTransfer","reviewBusy") -> {
                val ignored=id=="reviewIgnored"
                val ownership=id=="reviewOwnership"
                val transfer=id=="reviewTransfer"
                val family=if(transfer) com.baraa.masroof.domain.model.MessageFamily.TRANSFER_IN else com.baraa.masroof.domain.model.MessageFamily.PURCHASE
                val locale=androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
                val date=java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy",locale)
                val reason=when {ignored->"user_ignored_transaction";ownership->"purchase_instrument_ownership_unknown";transfer->"transfer_pending_match";else->"needs_review"}
                val detail=ReviewDetailUi("fixture","fixture",if(transfer) com.baraa.masroof.domain.model.ReviewKind.PENDING_MATCH else com.baraa.masroof.domain.model.ReviewKind.NEEDS_REVIEW,
                    if(ignored) R.string.review_status_ignored else if(transfer) R.string.review_kind_pending_match else R.string.review_kind_needs_review,
                    "ALJAZIRA",if(transfer) "حوالة واردة\\nحساب: 3001\\nمبلغ: SAR 50.00\\nمن: Sender" else "شراء عبر الإنترنت\\nبطاقة: 7271\\nمبلغ: SAR 50.00\\nلدى: NOON",
                    date.format(java.time.LocalDate.parse("2026-10-08")),null,family,
                    com.baraa.masroof.presentation.locale.formatLocalizedMoney(com.baraa.masroof.core.money.Money.of("50",com.baraa.masroof.core.money.Currency.SAR)),
                    if(transfer) null else "NOON",if(transfer) "Sender" else null,listOf(reason),emptyList(),transfer,transfer,!ignored && !ownership && !transfer,!ignored && !transfer,
                    ownershipCard=if(ownership) com.baraa.masroof.domain.model.CardReference(com.baraa.masroof.domain.model.Bank.BANK_ALJAZIRA,"7271") else null,
                    showOwnershipActions=ownership,showRestoreActions=ignored,resolvedAtLabel=if(ignored) date.format(java.time.LocalDate.parse("2026-10-09")) else null)
                ReviewDetailCaptureBridge(detail,resolving=id=="reviewBusy")
            }
            "transactions" -> TransactionListScreen(''')
test.write_text(text)
(destination / 'UI_REFERENCE.json').write_text(json.dumps({
    'production_commit': '0d86c5d3', 'source': source,
    'source_sha256': hashlib.sha256((destination / source).read_bytes()).hexdigest(),
    'fixture_transaction': 'ref-11', 'data': 'Synthetic FidelityFixtures, no user database',
    'modified_source': 'Working production sources untouched. Temporary ReviewScreen keeps all original bytes and appends a test-only private-renderer bridge.',
    'review_source': review_source, 'review_original_sha256': hashlib.sha256(review_original).hexdigest(),
    'review_original_prefix_preserved': review_file.read_bytes().startswith(review_original)
}, ensure_ascii=False, indent=2) + '\n')
print(destination)
