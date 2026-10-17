package com.flashing.tool.ui

import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.flashing.tool.R

/**
 * Hub layar utama — tiga pintu fitur: Flasher (BROM/DA), Fastboot Terminal,
 * dan Force Fastboot (MTK Preloader).
 */
class HomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        val cardFlasher = findViewById<View>(R.id.cardFlasher)
        val cardFastboot = findViewById<View>(R.id.cardFastboot)
        val cardForce = findViewById<View>(R.id.cardForce)

        staggerIn(cardFlasher, 0)
        staggerIn(cardFastboot, 80)
        staggerIn(cardForce, 160)

        cardFlasher.setOnClickListener {
            bounce(it)
            open(MainActivity::class.java)
        }
        cardFastboot.setOnClickListener {
            bounce(it)
            open(FastbootActivity::class.java)
        }
        cardForce.setOnClickListener {
            bounce(it)
            open(ForceFastbootActivity::class.java)
        }
    }

    private fun open(cls: Class<*>) {
        val intent = Intent(this, cls)
        val opts = ActivityOptions.makeCustomAnimation(
            this, R.anim.slide_up_in, R.anim.fade_out
        )
        startActivity(intent, opts.toBundle())
    }

    private fun staggerIn(view: View, delayMs: Long) {
        view.alpha = 0f
        view.translationY = 36f
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(delayMs)
            .setDuration(380)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun bounce(view: View) {
        view.animate()
            .scaleX(0.97f).scaleY(0.97f)
            .setDuration(70)
            .withEndAction {
                view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            .start()
    }
}
