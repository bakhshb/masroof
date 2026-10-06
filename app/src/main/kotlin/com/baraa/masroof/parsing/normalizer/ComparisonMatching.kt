package com.baraa.masroof.parsing.normalizer

import com.baraa.masroof.core.text.ArabicTextFolding

/**
 * Builds a regex for matching against [com.baraa.masroof.parsing.model.NormalizedSms.comparisonBody].
 *
 * The pattern is Arabic-folded (not lowercased, so escapes like `\p{L}` / `\S` survive);
 * write Latin literals in lowercase or pass [RegexOption.IGNORE_CASE].
 */
fun comparisonRegex(pattern: String, vararg options: RegexOption): Regex =
    Regex(ArabicTextFolding.foldArabic(pattern), options.toSet())

/**
 * Keyword match with the same Arabic equivalence as `comparisonBody`. Folds the receiver
 * as well, so it is safe on text that was not produced by [MessageNormalizer].
 */
fun String.containsComparison(keyword: String, ignoreCase: Boolean = false): Boolean =
    ArabicTextFolding.foldArabic(this).contains(ArabicTextFolding.foldArabic(keyword), ignoreCase)
