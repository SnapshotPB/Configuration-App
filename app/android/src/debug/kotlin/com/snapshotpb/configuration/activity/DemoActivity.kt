package com.snapshotpb.configuration.activity

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.snapshotpb.configuration.activity.MainActivity.Companion.EXTRA_FAKE_BOARD

/**
 * The Demo launcher entry of a debug build. It starts the app with the simulated board that
 * `FakeBoard` provides, so a demonstration needs no marker and no Bluetooth radio.
 *
 * It is a trampoline, and it holds no UI: it starts [MainActivity] with the fake-board extra and
 * finishes. `FLAG_ACTIVITY_CLEAR_TASK` drops whatever ran before, which gives two guarantees. The
 * demo starts clean even when the app already runs, and only one [MainActivity] survives — a second
 * one would answer every Bluetooth request a second time from its own collector.
 *
 * The normal launcher entry starts [MainActivity] with no extra, which turns the simulated board off
 * again. So the entry you tap decides the mode, every time.
 */
class DemoActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(EXTRA_FAKE_BOARD, true)
        )
        finish()
    }
}
