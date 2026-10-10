package com.baraa.masroof.instrumentation

import android.app.Application
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import com.baraa.masroof.application.sms.DebugProcessHalt

/**
 * Removes the M13 halt request before [com.baraa.masroof.MasroofApplication.onCreate]
 * when the instrumentation argument `m13Resume` is true.
 *
 * `onCreate` receives the bundle before [callApplicationOnCreate] runs
 * `Application.onCreate` on current platform versions. The arm phase also plants
 * `m13-resume-next`, which application startup consumes if this runner has not
 * already cleared the halt.
 */
class MasroofAndroidTestRunner : AndroidJUnitRunner() {
    private var resume: Boolean = false

    override fun onCreate(arguments: Bundle?) {
        resume = arguments?.getString(RESUME_ARGUMENT) == "true"
        super.onCreate(arguments)
    }

    override fun callApplicationOnCreate(app: Application) {
        if (resume) {
            DebugProcessHalt.clearRequest(app.filesDir)
        }
        super.callApplicationOnCreate(app)
    }

    companion object {
        const val RESUME_ARGUMENT: String = "m13Resume"
    }
}
