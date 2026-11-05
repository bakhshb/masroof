package com.baraa.masroof.analytics

import java.util.Locale

/** Spending categories. Names are persisted in `financial_transaction.categoryId`; never rename one. */
enum class SpendingCategory {
    GROCERIES, FOOD_DELIVERY, RESTAURANTS, SHOPPING, INSTALLMENTS, FUEL_CAR, HEALTH,
    BILLS_TELECOM, SUBSCRIPTIONS, TRANSPORT_TRAVEL, CHARITY, PERSONAL_CARE, OTHER;

    companion object {
        fun fromStored(value: String?): SpendingCategory? = entries.firstOrNull { it.name == value }
    }
}

/**
 * First guess from the merchant name only: well-known Saudi brands and generic words. The user's own
 * choice for a merchant always wins; an unknown name stays OTHER rather than being guessed.
 * Order matters: delivery apps and instalment providers are checked before broad words like "food".
 */
object MerchantCategoryRules {
    private val rules: List<Pair<SpendingCategory, List<String>>> = listOf(
        SpendingCategory.INSTALLMENTS to listOf("tamara", "tabby", "تمارا", "تابي"),
        SpendingCategory.FOOD_DELIVERY to listOf("keeta", "jahez", "hungerstation", "toyou", "mrsool", "marsool",
            "the chefz", "careem food", "جاهز", "هنقرستيشن", "كيتا", "مرسول"),
        SpendingCategory.GROCERIES to listOf("bin dawood", "bindawood", "panda", "danube", "tamimi", "spinneys", "othaim",
            "lulu", "carrefour", "farm superstores", "ninja", "amazon now", "nesto", "manuel", "butcher", "bakery", "hatab",
            "grocer", "supermarket", "hypermarket", "بنده", "الدانوب", "التميمي", "العثيم", "بقالة", "مخبز", "ملحمة", "تموينات"),
        SpendingCategory.RESTAURANTS to listOf("restaurant", "restau", "resta ", "matam", "mtam", "kitchen", "coffee", "cafe",
            "dunkin", "starbucks", "juice", "kudu", "albaik", "mcdonald", "burger", "pizza", "shawarma", "grill", "bakery cafe",
            "مطعم", "مطاعم", "مقهى", "كافيه", "قهوة"),
        SpendingCategory.FUEL_CAR to listOf("petromin", "wafi energy", "aldrees", "sasco", "naft", "gas station", " gas ",
            "fuel", "motor laundry", "car wash", "محطة", "وقود", "بترومين", "الدريس", "ساسكو"),
        SpendingCategory.HEALTH to listOf("hospital", "pharmac", "nahdi", "clinic", "medical", "nmc", "dental", "dawaa",
            "whites", "مستشفى", "صيدلية", "صيدليات", "عيادة", "النهدي"),
        SpendingCategory.BILLS_TELECOM to listOf("saudi electricity", "national water", "stcpostpaid", "stcrecharges",
            "stc recharge", "mobily", "zain", "safari telecom", "virgin mobile", "الكهرباء", "المياه", "موبايلي", "زين"),
        SpendingCategory.SUBSCRIPTIONS to listOf("chatgpt", "openai", "anthropic", "claude", "youtube", "netflix",
            "shahid", "spotify", "xbox", "playstation", "apple.com", "icloud", "google", "microsoft", "osn", "starzplay", "شاهد"),
        SpendingCategory.TRANSPORT_TRAVEL to listOf("careem", "uber", "jeeny", "rail", "saudia", "flynas", "flyadeal",
            "airline", "hotel", "booking", "parking", "قطار", "الخطوط", "فندق"),
        SpendingCategory.CHARITY to listOf("ehsan", "charity", "sadaqa", "zakat", "احسان", "إحسان", "خيرية", "صدقة"),
        SpendingCategory.PERSONAL_CARE to listOf("salon", "barber", "laundry", " spa ", "صالون", "حلاق", "مغسلة"),
        SpendingCategory.SHOPPING to listOf("amazon", "noon", "jarir", "extra", "ikea", "home center", "centerpoint", "max ",
            "redtag", "namshi", "shein", "mall", "furniture", "bed bath", "arabianoud", "arabian oud", "library", "bookstore", "جرير", "نون",
            "امازون", "أمازون", "مكتبة", "عطور"),
    )

    fun categorize(merchant: String?): SpendingCategory {
        if (merchant.isNullOrBlank()) return SpendingCategory.OTHER
        // Spaces around the name let short words like " gas " match only whole words at the edges.
        val name = " " + merchant.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim() + " "
        return rules.firstOrNull { (_, words) -> words.any(name::contains) }?.first ?: SpendingCategory.OTHER
    }
}
