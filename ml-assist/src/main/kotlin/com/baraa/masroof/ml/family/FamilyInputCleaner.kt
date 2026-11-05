package com.baraa.masroof.ml.family

import com.baraa.masroof.analytics.hash

/** Constructed only by the shared cleaner (or its validated persistence decoder). No raw SMS. */
class CleanFamilyFeatures private constructor(
    val title: String,
    val terms: Map<String, Int>,
    val template: String,
) {
    companion object {
        internal fun create(title: String, terms: Map<String, Int>, template: String) =
            CleanFamilyFeatures(title, terms.toSortedMap(), template)
    }
}

enum class CleaningRejection { EMPTY, TOO_LARGE }
data class FamilyCleaningResult(val features: CleanFamilyFeatures?, val rejection: CleaningRejection?)

/** Task-specific minimization, after the bank's normalization. Never remove negation/security words. */
object FamilyInputCleaner {
    const val VERSION = "sms-family-clean-v3"
    const val FEATURES_VERSION = "bank-family-title-v2"
    private val spaces = Regex("\\s+")
    private val words = Regex("[\\p{L}]+")
    private val controlsAndSeparators = Regex("[\\p{Cc}\\p{Z}]+")
    private val identifiers = Regex("(?:https?://|www\\.)\\S+|\\S+@\\S+|(?i:sa[0-9]{6,}|btms[0-9]+)|[\\p{N}][\\p{N}.,:/+\\-]*")
    private val currencyCodes = Regex("\\b(?:sar|usd|eur|gbp|aed)\\b", RegexOption.IGNORE_CASE)
    private val characterTerm = Regex("c[3-5]:[\\p{L} ]+")
    // Reviewed structural vocabulary, not user names/merchants. Only these words are stored as terms;
    // any other title word still shapes the template hash, so no qualification is ever dropped.
    private val titleWords = """
        internet online purchase apple pay pos contactless google samsung one time password for
        card credit debit refund payment transfer incoming outgoing local internal external accepted
        declined rejected cancelled reversed reversal request pending failed not no never otp verification
        balance notice statement issue cash withdrawal fee loan installment bill login account
        استرداد استرجاع مبلغ عملية شراء عبر نقاط البيع من الانترنت حوالة مالية صادرة واردة داخلية محلية
        راتب مقبولة حسابك الجاري خصم قسط تمويل سداد خاص شخصي رسوم خدمة سحب نقدي فاتورة بطاقة
        ايتمانية ائتمانية تسديد اشعار رصيد اصدار كشف حساب اسم المستفيد رمز التحقق الخاص بك هو
        لا تشاركه مع اي شخص تم تسجيل الدخول الي بنجاح تطبيق بنك الجزيرة لعملية طلب مرفوضة رفض
        الغاء ملغاة عكس فشل غير مكتملة قيد التنفيذ لم يتم ليست ناجحة تحويل دولية
    """.trimIndent().split(spaces).toSet()
    // Labels arrive normalized by the engine (no hamza or marks, ى as ي, lowercase), so they are written that way here.
    private val labels = setOf(
        "بطاقة", "بطاقة ايتمانية", "بطاقة مدي", "حساب", "من حساب", "خصمت من حساب", "اودعت الي حساب",
        "من", "الي", "لدي", "مبلغ", "مبلغ العملية", "المبلغ", "العملة", "القسط", "المبلغ المتبقي", "ل", "في",
        "الدولة", "رقم المعاملة", "المرجع", "المعرف البديل الايبان", "رسوم العمليات الدولية", "سعر الصرف",
        "الرصيد المتاح", "اجمالي المستحق", "الرصيد", "بنك المرسل", "بنك المستفيد", "الاسم المختصر", "حالة",
        "حساب المرسل", "حساب المستفيد", "رقم الحساب", "ايبان", "مرسل", "مستفيد", "الجهة", "مفوتر", "المفوتر",
        "القسط الشهري", "القسط المستحق", "اجمالي المبلغ المتبقي", "الرصيد المتبقي", "المبلغ المستحق",
        "تاريخ", "تاريخ الاستحقاق", "نوع التمويل",
        "credit card", "debit card", "mada card", "card", "account", "from account", "to account", "from a/c",
        "to a/c", "from", "to", "at", "on", "of", "amount", "currency", "balance", "available balance",
        "available balance is", "due amount", "reference", "fee", "merchant", "biller",
    )
    private val titleValueFields = setOf("اسم المستفيد", "الاسم المختصر", "من", "الي", "لدي", "from", "to", "at")

    fun clean(normalizedTitle: String, normalizedFieldLabels: List<String>): FamilyCleaningResult {
        if (normalizedTitle.length > 512 || normalizedFieldLabels.size > 128) return rejected(CleaningRejection.TOO_LARGE)
        fun compact(value: String) = value.filterNot { Character.getType(it) == Character.FORMAT.toInt() }
            .replace(controlsAndSeparators, " ").replace(spaces, " ").trim()
        var title = compact(normalizedTitle)
        // Explicit value fields are structural headings, never personal values.
        if (title.substringBefore(':').trim() in titleValueFields) title = title.substringBefore(':')
        // Entire mixed identifiers/links/emails disappear, never only their numeric part.
        title = title.replace(identifiers, " ")
        title = title.replace(currencyCodes, " ")
        val tokens = words.findAll(title).map { it.value }.toList()
        if (tokens.isEmpty()) return rejected(CleaningRejection.EMPTY)
        if (tokens.size > 64) return rejected(CleaningRejection.TOO_LARGE)
        // Every bank's wording learns: the full title is hashed, but unreviewed words never leave the hash.
        val cleanTitle = tokens.joinToString(" ")
        val known = tokens.filter { it in titleWords }
        val knownTitle = known.joinToString(" ")
        val fieldLabels = normalizedFieldLabels.map(::compact).filter { it in labels }.distinct().sorted()
        val features = mutableListOf<String>()
        features += known.map { "w:$it" }
        features += tokens.zipWithNext { a, b -> "b:$a $b" }.filter { it.substring(2).split(' ').all(titleWords::contains) }
        if (known.size == tokens.size) for (n in 3..5) if (knownTitle.length >= n) features += knownTitle.windowed(n).map { "c$n:$it" }
        val terms = features.groupingBy { it }.eachCount().mapValues { it.value.coerceAtMost(5) }
        val template = sha256("$VERSION|$FEATURES_VERSION|$cleanTitle|${fieldLabels.joinToString("|")}")
        return FamilyCleaningResult(CleanFamilyFeatures.create(cleanTitle, terms, template), null)
    }

    /** Cache labels are data, not a bypass for the cleaner's vocabulary and feature bounds. */
    fun validTerms(terms: Map<String, Int>): Boolean = terms.size <= 2048 && terms.all { (term, count) ->
        count in 1..5 && term.length <= 260 && when {
            term.startsWith("w:") -> term.substring(2) in titleWords
            term.startsWith("b:") -> term.substring(2).split(' ').let { it.size == 2 && it.all(titleWords::contains) }
            term.matches(characterTerm) -> term.substring(3).length == term[1].digitToInt()
            else -> false
        }
    }

    private fun rejected(reason: CleaningRejection) = FamilyCleaningResult(null, reason)
    fun sha256(value: String): String = hash(value)
}
