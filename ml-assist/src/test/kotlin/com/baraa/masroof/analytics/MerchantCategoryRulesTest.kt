package com.baraa.masroof.analytics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Fictional merchant strings in the shapes card terminals send (branch numbers, payment prefixes, cut-off names). */
class MerchantCategoryRulesTest {
    private fun assertCategory(category: SpendingCategory, vararg names: String) =
        names.forEach { assertEquals(category, MerchantCategoryRules.categorize(it), it) }

    @Test fun wellKnownMerchantsGetAFirstGuess() {
        assertCategory(SpendingCategory.FOOD_DELIVERY, "Keeta", "MYSR*Jahez", "HUNGERSTATION LLC")
        assertCategory(SpendingCategory.GROCERIES, "BIN DAWOOD 101", "PANDA 100", "Danube(D100)", "TAMIMI MARKETS 10", "Amazon Now")
        assertCategory(SpendingCategory.RESTAURANTS, "TEST GRILL RESTAURANT", "Matam Test", "COFFEE TEST", "DUNKIN 100")
        assertCategory(SpendingCategory.SHOPPING, "Amazon SA", "JARIR BOOKSTORE", "Noon", "MAX 100 TEST MALL")
        assertCategory(SpendingCategory.INSTALLMENTS, "Tamara", "Tabby")
        assertCategory(SpendingCategory.FUEL_CAR, "PETROMIN FUEL-10 Test", "WAFI ENERGY CO", "TEST GAS")
        assertCategory(SpendingCategory.HEALTH, "TEST GENERAL HOSPITAL", "ALNAHDI PHARMACY100", "TEST MEDICAL CLINIC")
        assertCategory(SpendingCategory.BILLS_TELECOM, "SAUDI ELECTRICITY COMP", "STCPostpaidSrvc", "Mobily")
        assertCategory(SpendingCategory.SUBSCRIPTIONS, "Google YouTubePremium", "NETFLIX.COM", "Microsoft*Xbox")
        assertCategory(SpendingCategory.CHARITY, "EHSAN", "TEST CHARITY")
        assertCategory(SpendingCategory.TRANSPORT_TRAVEL, "CAREEM RIDE", "TEST RAIL")
        assertCategory(SpendingCategory.RESTAURANTS, "مطعم تجربة")
        assertCategory(SpendingCategory.HEALTH, "صيدلية تجربة")
    }

    @Test fun unknownOrPersonalNamesStayOtherInsteadOfBeingGuessed() {
        assertCategory(SpendingCategory.OTHER, "Test Person", "TEST CO 100", "", "   ")
        assertEquals(SpendingCategory.OTHER, MerchantCategoryRules.categorize(null))
        // Short words match whole words only at the edges ("gas" is not inside "Vegas Store").
        assertCategory(SpendingCategory.OTHER, "Vegas Store", "Cloudflare")
    }

    @Test fun storedNamesAreStableAndUnknownOnesAreIgnored() {
        SpendingCategory.entries.forEach { assertEquals(it, SpendingCategory.fromStored(it.name)) }
        assertEquals(null, SpendingCategory.fromStored("RETIRED_CATEGORY"))
        assertEquals(null, SpendingCategory.fromStored(null))
    }
}
