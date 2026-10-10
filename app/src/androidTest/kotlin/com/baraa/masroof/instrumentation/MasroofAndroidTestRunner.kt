package com.baraa.masroof.instrumentation

import android.app.Application
import androidx.test.runner.AndroidJUnitRunner
import com.baraa.masroof.application.sms.DebugProcessHalt

/**
 * Removes the M13 halt request before [com.baraa.masroof.MasroofApplication.onCreate]
 * when the instrumentation argument `m13Resume` is true.
 *
 * [android.app.Instrumentation.callApplicationOnCreate] is what runs `Application.onCreate`.
 * Deleting `filesDir/m13-halt-after` here lets startup schedule live SMS work without
 * parking on the stage that the previous process already durable-wrote.
 */
class MasroofAndroidTestRunner : AndroidJUnitRunner() {
    override fun callApplicationOnCreate(app: Application) {
        if (arguments?.getString(RESUME_ARGUMENT) == "true") {
            DebugProcessHalt.clearRequest(app.filesDir)
        }
        super.callApplicationOnCreate(app)
    }

    companion object {
        const val RESUME_ARGUMENT: String = "m13Resume"
    }
}
