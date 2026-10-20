"""Prepare an isolated, pinned production capture harness without editing the working app.

Usage: python3 lab/beta/tools/prepare_transaction_detail_reference.py /tmp/new-reference
Then build :app:assembleDebug :app:assembleDebugAndroidTest in that directory and run
ApprovedFidelityMatrixTest with capturePages=transactionDetail on the isolated emulator.
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
test.write_text(text)
(destination / 'TRANSACTION_DETAIL_REFERENCE.json').write_text(json.dumps({
    'production_commit': '0d86c5d3', 'source': source,
    'source_sha256': hashlib.sha256((destination / source).read_bytes()).hexdigest(),
    'fixture_transaction': 'ref-11', 'data': 'Synthetic FidelityFixtures, no user database',
    'modified_source': 'Only the temporary Android test harness; production main source is unchanged.'
}, ensure_ascii=False, indent=2) + '\n')
print(destination)
