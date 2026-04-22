package org.read.mobile

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class ShareEntryActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        routeToMainActivity(intent)
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeToMainActivity(intent)
        finish()
        overridePendingTransition(0, 0)
    }

    private fun routeToMainActivity(sourceIntent: Intent?) {
        if (sourceIntent == null) {
            return
        }

        val forwardedIntent = Intent(sourceIntent).apply {
            setClass(this@ShareEntryActivity, MainActivity::class.java)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        startActivity(forwardedIntent)
    }
}
