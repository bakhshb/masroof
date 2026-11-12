package com.baraa.masroof.application.dashboard

/** Whether account-flow bucketing runs for the full fleet or one owned account. */
enum class AccountFlowScopeMode {
    /** All owned accounts — orphan typed outflows count once at fleet level. */
    Fleet,

    /** Single owned account — requires a resolved source/destination for that account. */
    SingleAccount,
}
