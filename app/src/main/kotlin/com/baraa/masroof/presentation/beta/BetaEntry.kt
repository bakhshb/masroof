package com.baraa.masroof.presentation.beta

import android.app.Activity
import android.content.Context
import android.content.Intent

/**
 * The only way to open Masroof Beta.
 *
 * Developer settings calls [open]. There is no launcher icon, view action, or
 * public deep link. Leaving Beta finishes this activity and leaves the
 * production activity where it was.
 */
object BetaEntry {
    fun open(context: Context) {
        context.startActivity(intent(context))
    }

    fun intent(context: Context): Intent =
        Intent(context, BetaActivity::class.java).apply {
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
}
