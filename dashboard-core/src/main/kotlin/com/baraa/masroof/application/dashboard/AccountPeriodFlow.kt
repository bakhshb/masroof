package com.baraa.masroof.application.dashboard

fun CurrentAccountSummary.externalMovement(): AccountFlowSummary =
    accountFlow().externalSummary()

fun CurrentAccountSummary.cashPosition(): AccountFlowSummary =
    accountFlow().accountSummary()
