package io.github.nezdelion.dlnatvcast

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import io.github.nezdelion.dlnatvcast.cast.CastService

/**
 * Share-sheet entry point without UI: hands the shared URIs (with their read grants) to
 * [CastService] and opens the main screen.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty().filterNotNull()
            else -> emptyList()
        }.ifEmpty {
            // Some apps only fill ClipData.
            val clip = intent.clipData
            if (clip == null) emptyList() else (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        }
        // Only URIs we were granted can be handed on; granting the others would throw.
        val (readable, unreadable) = uris.partition {
            checkCallingOrSelfUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED
        }
        ContextCompat.startForegroundService(this, CastService.shareIntent(this, readable, unreadable.size, intent.type))
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
        if (Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
    }
}
