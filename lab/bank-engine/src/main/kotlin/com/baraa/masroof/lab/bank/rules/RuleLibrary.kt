package com.baraa.masroof.lab.bank.rules

import com.baraa.masroof.lab.bank.*

/** Initial independent literal vocabulary. Not a bank detector or a claim of corpus coverage. */
object RuleLibrary {
    fun initial(): BankRuleSchema = BankRuleSchema(revision = 1, rules = listOf(
        rule("purchase", MessageFamily.PURCHASE, listOf("شراء", "purchase")),
        rule("refund", MessageFamily.REFUND, listOf("استرداد", "refund"), MoneyDirection.INCOMING),
        rule("withdrawal", MessageFamily.WITHDRAWAL, listOf("سحب نقدي", "cash withdrawal")),
        rule("fee", MessageFamily.FEE, listOf("خصم رسوم", "fee charged")),
        rule("balance", MessageFamily.BALANCE_NOTICE, listOf("اشعار رصيد", "balance notification")),
        rule("notice", MessageFamily.NON_FINANCIAL, listOf("تم تسجيل الدخول", "login notification")),
    ))

    /** One interpreter, versioned literal profiles. Bank aliases remain caller-owned identity data. */
    fun current(): BankRuleSchema = initial().let { previous -> previous.copy(revision = 7,
        network = NetworkRules(sourceBankLabels = listOf("source bank", "from bank", "بنك المرسل", "البنك المرسل"),
            destinationBankLabels = listOf("destination bank", "to bank", "بنك المستفيد"),
            networkLabels = listOf("bank network", "network", "شبكة الحوالة")),
        rules = (previous.rules + listOf(
            rule("card.payment", MessageFamily.CARD_PAYMENT, listOf("سداد بطاقة", "card payment", "بطاقة ائتمانية: تسديد")),
            rule("bill.payment", MessageFamily.BILL_PAYMENT, listOf("سداد فاتورة", "bill payment")),
            rule("installment", MessageFamily.FINANCING_INSTALLMENT, listOf("قسط تمويل", "loan repayment")),
            rule("transfer.in", MessageFamily.TRANSFER_IN, incomingTransferPhrases(), MoneyDirection.INCOMING),
            rule("transfer.out", MessageFamily.TRANSFER_OUT, outgoingTransferPhrases()),
            rule("statement", MessageFamily.NON_FINANCIAL, listOf("اصدار كشف حساب", "بطاقة ائتمانية: اصدار كشف حساب")),
        )).map { rule -> rule.copy(phrases = rule.phrases + when (rule.family) {
            MessageFamily.PURCHASE -> listOf("credit card purchase", "debit card purchase")
            MessageFamily.REFUND -> listOf("استرداد مبلغ عملية شراء عبر نقاط البيع", "بطاقة ائتمانية: استرداد مبلغ", "credit card: refund", "debit card: refund")
            MessageFamily.FEE -> listOf("رسوم خدمة")
            else -> emptyList()
        }, detailLabels = mapOf(
            "dueAmount" to listOf("المبلغ المستحق", "اجمالي المبلغ المستحق", "اجمالي المستحق", "المبلغ المتبقي", "outstanding balance", "due amount"),
            "foreignAmount" to listOf("المبلغ الاجنبي", "foreign amount"),
            "counterparty" to listOf("جهة التمويل", "counterparty") + when(rule.family) {
                MessageFamily.FINANCING_INSTALLMENT -> listOf("لـ")
                MessageFamily.TRANSFER_IN -> listOf("اسم المرسل", "من", "from")
                MessageFamily.TRANSFER_OUT -> listOf("اسم المستفيد", "الى")
                else -> emptyList()
            },
            "transactionReference" to listOf("رقم المعاملة", "transaction reference"),
            "biller" to listOf("المفوتر", "biller"),
            "billerCode" to listOf("رمز المفوتر", "biller code"),
            "paymentDueDate" to listOf("تاريخ الاستحقاق", "payment due date"),
        ), labels = rule.labels + mapOf(
            ExtractionField.CARD to listOf("بطاقة", "card", "credit card", "debit card", "بطاقة ائتمانية", "بطاقة مدى"),
            ExtractionField.AMOUNT to listOf("مبلغ العملية", "بمبلغ", "مبلغ", "amount", "of", "القيمة", "value") + if(rule.family == MessageFamily.FINANCING_INSTALLMENT) listOf("القسط") else emptyList(),
            ExtractionField.AVAILABLE_BALANCE to listOf("الرصيد المتاح", "رصيد", "available balance", "available balance is"),
            ExtractionField.MERCHANT to listOf("لدى", "merchant", "at") + if(rule.family == MessageFamily.REFUND) listOf("من", "from") else emptyList(),
            ExtractionField.SOURCE_ACCOUNT to listOf("من حساب", "from account", "رقم حساب المرسل") +
                (if(rule.family != MessageFamily.TRANSFER_IN) listOf("خصمت من حساب") else emptyList()) +
                (if(rule.family in setOf(MessageFamily.CARD_PAYMENT, MessageFamily.FINANCING_INSTALLMENT, MessageFamily.TRANSFER_OUT)) listOf("من") else emptyList()) +
                (if(rule.family == MessageFamily.WITHDRAWAL) listOf("حساب رقم", "account number") else emptyList()),
            ExtractionField.DESTINATION_ACCOUNT to listOf("الى حساب", "to account", "اودعت الى حساب", "deposited to account") + if(rule.family in setOf(MessageFamily.TRANSFER_IN, MessageFamily.TRANSFER_OUT)) listOf("الى", "المعرف البديل \\الايبان") else emptyList(),
        ),
            observations = buildMap {
                if(rule.family in setOf(MessageFamily.PURCHASE,MessageFamily.REFUND)) put("fee.sar_unlabeled",listOf("رسوم العمليات الدولية"))
                if (rule.family in setOf(MessageFamily.PURCHASE, MessageFamily.REFUND, MessageFamily.CARD_PAYMENT, MessageFamily.BALANCE_NOTICE, MessageFamily.NON_FINANCIAL)) {
                    put("card.credit", listOf("credit", "credit card", "ائتمانية"))
                    put("card.debit", listOf("debit", "debit card", "مدى"))
                    put("card.statement", listOf("statement", "كشف"))
                    put("card.available_balance", listOf("available balance", "الرصيد المتاح"))
                    put("card.credit_balance", listOf("available balance", "الرصيد المتاح"))
                }
                if (rule.family == MessageFamily.PURCHASE) {
                    put("purchase.pos", listOf("pos", "نقاط البيع")); put("purchase.online", listOf("online", "internet", "عبر الانترنت"))
                }
                if (rule.family == MessageFamily.FINANCING_INSTALLMENT) {
                    put("loan.personal", listOf("personal", "شخصي")); put("loan.auto", listOf("auto", "سيارة"))
                    put("loan.mortgage", listOf("mortgage", "عقاري"))
                }
                if (rule.family in setOf(MessageFamily.TRANSFER_IN, MessageFamily.TRANSFER_OUT)) {
                    put("network.intra", listOf("intra-bank", "داخل البنك", "داخلية", "الى حسابك الجاري")); put("network.inter", listOf("inter-bank", "بين البنوك"))
                }
                if (rule.family == MessageFamily.TRANSFER_IN) put("income.salary", listOf("salary", "راتب"))
            }) })
    }

    internal fun incomingTransferPhrases() = listOf("حوالة واردة", "حوالة مالية واردة", "تحويل وارد", "incoming transfer", "fund transfer credited", "salary credited")
    internal fun outgoingTransferPhrases() = listOf("حوالة صادرة", "حوالة مالية صادرة", "تحويل صادر", "outgoing transfer", "fund transfer debited", "transfer sent")

    private fun rule(id: String, family: MessageFamily, phrases: List<String>,
        direction: MoneyDirection = MoneyDirection.OUTGOING) = BankRule("bank.$id", family, phrases,
        direction = direction, labels = mapOf(
            ExtractionField.AMOUNT to listOf("مبلغ العملية", "بمبلغ", "amount"),
            ExtractionField.CARD to listOf("بطاقة", "card"),
            ExtractionField.SOURCE_ACCOUNT to listOf("من حساب", "from account"),
            ExtractionField.DESTINATION_ACCOUNT to listOf("الي حساب", "to account"),
            ExtractionField.MERCHANT to listOf("لدي", "merchant"),
            ExtractionField.AVAILABLE_BALANCE to listOf("الرصيد المتاح", "available balance"),
        ))
}
