package com.example.devicepolicycontroller

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/** The device-owner-selected handler used when external web links are blocked. */
class BlockedLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Toast.makeText(this, "External web links are blocked by device policy.", Toast.LENGTH_LONG).show()
        finish()
    }
}
