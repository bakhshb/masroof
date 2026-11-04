package com.baraa.masroof.presentation.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateMessageOutsideCardTest {
    @Test
    fun statusTheCardAlreadyShowsIsNotRepeated() {
        assertNull(updateMessageOutsideCard(AppUpdateUiState.Idle, AppUpdateMessage.CHECK_FAILED))
        assertNull(updateMessageOutsideCard(AppUpdateUiState.UpToDate, AppUpdateMessage.UP_TO_DATE))
    }

    @Test
    fun messagesTheCardDoesNotShowStay() {
        assertEquals(AppUpdateMessage.TOKEN_SAVED, updateMessageOutsideCard(AppUpdateUiState.Idle, AppUpdateMessage.TOKEN_SAVED))
        assertEquals(AppUpdateMessage.INSTALL_PERMISSION_REQUIRED,
            updateMessageOutsideCard(AppUpdateUiState.Idle, AppUpdateMessage.INSTALL_PERMISSION_REQUIRED))
    }
}
