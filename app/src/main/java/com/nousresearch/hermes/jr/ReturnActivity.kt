package com.nousresearch.hermes.jr

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Target of the hermesjr://signed-in link on the sign-in loopback page. Brings the existing
 * MainActivity back to the front and clears the browser tab that sits above it.
 */
class ReturnActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
