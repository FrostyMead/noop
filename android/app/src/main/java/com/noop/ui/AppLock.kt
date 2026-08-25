package com.noop.ui

import android.app.KeyguardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Local preference for the optional app gate. The phone OS, not NOOP, stores the credential. */
internal object AppLockPrefs {
    private const val KEY_ENABLED = "noop.appLockEnabled"

    fun isEnabled(context: Context): Boolean =
        NoopPrefs.of(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        NoopPrefs.of(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun isDeviceSecure(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
}

/** Pure lifecycle policy kept separate so the privacy-sensitive transition is unit-testable. */
internal object AppLockPolicy {
    fun shouldLockOnStop(enabled: Boolean, isChangingConfigurations: Boolean): Boolean =
        enabled && !isChangingConfigurations
}

@Composable
internal fun AppLockScreen(
    message: String?,
    onUnlock: () -> Unit,
    onOpenPhoneSecurity: () -> Unit,
) {
    Surface(color = Palette.surfaceBase, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = Palette.accent,
                modifier = Modifier.size(52.dp),
            )
            Spacer(Modifier.height(20.dp))
            Text("NOOP is locked", style = NoopType.title1, color = Palette.textPrimary)
            Spacer(Modifier.height(8.dp))
            Text(
                "Unlock with your fingerprint, face, or phone screen lock. " +
                    "Bluetooth collection can continue while this screen is locked.",
                style = NoopType.body,
                color = Palette.textSecondary,
                textAlign = TextAlign.Center,
            )
            if (!message.isNullOrBlank()) {
                Spacer(Modifier.height(14.dp))
                Text(
                    message,
                    style = NoopType.footnote,
                    color = Palette.strain066,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(24.dp))
            NoopButton(
                text = "Unlock",
                leadingIcon = Icons.Filled.Lock,
                fullWidth = true,
                modifier = Modifier.fillMaxWidth(),
                onClick = onUnlock,
            )
            Spacer(Modifier.height(10.dp))
            NoopButton(
                text = "Phone security settings",
                kind = NoopButtonKind.Tertiary,
                fullWidth = true,
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenPhoneSecurity,
            )
        }
    }
}
