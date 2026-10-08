package com.baraa.masroof.application.logging

object AppLogRedactor {
    private val tokenPatterns = listOf(
        Regex("""ghp_[A-Za-z0-9]{20,}"""),
        Regex("""github_pat_[A-Za-z0-9_]{20,}"""),
        Regex("""gho_[A-Za-z0-9]{20,}"""),
        Regex("""(?i)(authorization:\s*)(token|bearer)\s+\S+"""),
    )

    private val panLikePattern =
        Regex("""\b(?:\d[ -]?){11,18}\d\b""")

    private val saudiIbanPattern =
        Regex("""(?i)\bSA\d{2}[A-Z0-9]{20}\b""")

    private val labeledOtpPattern =
        Regex("""(?i)(?:otp|one[- ]time(?: password)?|verification code|رمز(?:\s+التحقق)?)\s*[:#]?\s*\d{4,8}""")

    fun redact(message: String): String {
        var sanitized = message
        tokenPatterns.forEach { pattern ->
            sanitized = sanitized.replace(pattern) { match ->
                when {
                    match.groups.size >= 2 && match.groups[1] != null ->
                        "${match.groups[1]!!.value}${match.groups[2]!!.value} [REDACTED]"
                    else -> "[REDACTED]"
                }
            }
        }
        sanitized = sanitized.replace(panLikePattern, "[REDACTED]")
        sanitized = sanitized.replace(saudiIbanPattern, "[REDACTED]")
        sanitized = sanitized.replace(labeledOtpPattern, "[REDACTED]")
        return sanitized
    }
}
