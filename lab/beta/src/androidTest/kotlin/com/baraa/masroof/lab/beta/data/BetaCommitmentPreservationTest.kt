package com.baraa.masroof.lab.beta.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.baraa.masroof.lab.beta.BetaContainer
import com.baraa.masroof.lab.beta.verification.BetaCommitmentPreservationChecks
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BetaCommitmentPreservationTest {
    @Test fun settingsWritesPreservePausedSourceLinkedCommitmentsAcrossReopen() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        BetaContainer.resetOffline(context)
        fun open()=Room.databaseBuilder(context,BetaDatabase::class.java,BetaDatabase.NAME).build()
        try {
            val initial=open()
            val expected=try {BetaCommitmentPreservationChecks.editsPreserveRegistryFacts(initial)} finally {initial.close()}
            val reopened=open()
            try {assertEquals(expected,reopened.dashboardConfigurationDao().commitments().single())} finally {reopened.close()}
        } finally {BetaContainer.resetOffline(context)}
    }
}
