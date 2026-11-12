package com.baraa.masroof.domain.model

import java.time.Instant

data class CommitmentPauseInterval(
    val pausedAt: Instant,
    val resumedAt: Instant? = null,
)
