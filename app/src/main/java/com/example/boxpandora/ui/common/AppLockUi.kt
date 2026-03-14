package com.example.boxpandora.ui.common

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.example.boxpandora.ui.main.viewmodel.AppLockMode
import com.example.boxpandora.ui.main.viewmodel.AppLockTimeout
import com.example.boxpandora.ui.theme.boxPandoraModalTokens

@Composable
fun AppLockModeSheet(
    selectedMode: AppLockMode,
    onDismiss: () -> Unit,
    onSelect: (AppLockMode) -> Unit
) {
    AppModalSheet(onDismiss = onDismiss) {
        ModalHeader(
            title = "Choose app lock",
            subtitle = "Use your phone's existing lock screen or create a private 4-digit passcode for Pandora."
        )
        ModalSelectableRow(
            label = "Phone lock",
            supportingText = "Use your device PIN, pattern, or password",
            assetIcon = "lock.svg",
            selected = selectedMode == AppLockMode.DEVICE_CREDENTIAL,
            onClick = { onSelect(AppLockMode.DEVICE_CREDENTIAL) }
        )
        ModalDivider(modifier = Modifier.padding(start = 30.dp))
        ModalSelectableRow(
            label = "4-digit passcode",
            supportingText = "Create a separate code stored in Pandora's database",
            assetIcon = "pin.svg",
            selected = selectedMode == AppLockMode.PIN,
            onClick = { onSelect(AppLockMode.PIN) }
        )
        ModalFooterAction(label = "Cancel", onClick = onDismiss)
    }
}

@Composable
fun AppLockTimeoutSheet(
    selectedTimeout: AppLockTimeout,
    onDismiss: () -> Unit,
    onSelect: (AppLockTimeout) -> Unit
) {
    AppModalSheet(onDismiss = onDismiss) {
        ModalHeader(
            title = "Re-lock timing",
            subtitle = "Choose how long Pandora stays unlocked after you leave the app."
        )
        AppLockTimeout.entries.forEachIndexed { index, timeout ->
            ModalSelectableRow(
                label = timeout.displayName,
                selected = timeout == selectedTimeout,
                onClick = { onSelect(timeout) }
            )
            if (index < AppLockTimeout.entries.lastIndex) {
                ModalDivider(modifier = Modifier.padding(start = 30.dp))
            }
        }
        ModalFooterAction(label = "Close", onClick = onDismiss)
    }
}

@Composable
fun AppPasscodeSetupDialog(
    title: String,
    subtitle: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val tokens = boxPandoraModalTokens()

    AppDialog(onDismiss = onDismiss) {
        ModalHeader(title = title, subtitle = subtitle)
        ModalTextField(
            value = pin,
            onValueChange = {
                pin = it.filter(Char::isDigit).take(4)
                error = null
            },
            placeholder = "Enter 4-digit passcode",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            visualTransformation = PasswordVisualTransformation()
        )
        ModalTextField(
            value = confirmPin,
            onValueChange = {
                confirmPin = it.filter(Char::isDigit).take(4)
                error = null
            },
            placeholder = "Confirm passcode",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            visualTransformation = PasswordVisualTransformation()
        )
        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.destructiveAccent
            )
        }
        AppDialogActionRow(
            dismissLabel = "Cancel",
            confirmLabel = confirmLabel,
            onDismiss = onDismiss,
            onConfirm = {
                when {
                    pin.length != 4 -> error = "Use exactly 4 digits."
                    confirmPin.length != 4 -> error = "Confirm the 4-digit passcode."
                    pin != confirmPin -> error = "Passcodes do not match."
                    else -> onConfirm(pin)
                }
            }
        )
    }
}

@Composable
fun AppPasscodeVerificationDialog(
    title: String,
    subtitle: String,
    confirmLabel: String,
    errorMessage: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var pin by remember { mutableStateOf("") }
    val tokens = boxPandoraModalTokens()

    AppDialog(onDismiss = onDismiss) {
        ModalHeader(title = title, subtitle = subtitle)
        ModalTextField(
            value = pin,
            onValueChange = { pin = it.filter(Char::isDigit).take(4) },
            placeholder = "Enter 4-digit passcode",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            visualTransformation = PasswordVisualTransformation()
        )
        errorMessage?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.destructiveAccent
            )
        }
        AppDialogActionRow(
            dismissLabel = "Cancel",
            confirmLabel = confirmLabel,
            onDismiss = onDismiss,
            onConfirm = {
                if (pin.length == 4) {
                    onConfirm(pin)
                }
            }
        )
    }
}

@Composable
fun AppLockRemovalConfirmationDialog(
    mode: AppLockMode,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val subtitle = when (mode) {
        AppLockMode.PIN -> "Removing the passcode requires confirmation and your current 4-digit code."
        AppLockMode.DEVICE_CREDENTIAL -> "Removing phone lock protection requires confirmation and your device lock."
        AppLockMode.NONE -> ""
    }

    AppDialog(onDismiss = onDismiss) {
        ModalHeader(
            title = "Remove app lock",
            subtitle = subtitle
        )
        AppDialogActionRow(
            dismissLabel = "Cancel",
            confirmLabel = "Continue",
            onDismiss = onDismiss,
            onConfirm = onConfirm
        )
    }
}

@Composable
fun AppLockGateDialog(
    mode: AppLockMode,
    errorMessage: String?,
    onUnlockWithPin: (String) -> Unit,
    onUnlockWithPhoneLock: () -> Unit,
    onClearError: () -> Unit
) {
    var pin by remember(mode) { mutableStateOf("") }
    val tokens = boxPandoraModalTokens()

    AppDialog(
        onDismiss = {},
        dismissOnClickOutside = false,
        dismissOnBackPress = false
    ) {
        ModalHeader(
            title = "Pandora is locked",
            subtitle = if (mode == AppLockMode.PIN) {
                "Enter your 4-digit passcode to continue."
            } else {
                "Unlock with your phone lock to continue using Pandora."
            }
        )

        if (mode == AppLockMode.PIN) {
            ModalTextField(
                value = pin,
                onValueChange = {
                    pin = it.filter(Char::isDigit).take(4)
                    onClearError()
                },
                placeholder = "Enter 4-digit passcode",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                visualTransformation = PasswordVisualTransformation()
            )
            errorMessage?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.destructiveAccent
                )
            }
            Button(
                onClick = { onUnlockWithPin(pin) },
                modifier = Modifier.fillMaxWidth(),
                enabled = pin.length == 4
            ) {
                Text("Unlock")
            }
        } else {
            Text(
                text = "Phone lock uses the same PIN, password, or pattern you already use to unlock your device.",
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.secondaryText
            )
            Button(
                onClick = onUnlockWithPhoneLock,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Unlock with phone lock")
            }
        }
    }
}

@Composable
private fun AppDialogActionRow(
    dismissLabel: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onDismiss) {
            Text(text = dismissLabel)
        }
        Button(onClick = onConfirm) {
            Text(text = confirmLabel, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
        }
    }
}

fun createDeviceCredentialIntent(
    context: Context,
    title: String,
    description: String
): Intent? {
    val keyguardManager = context.getSystemService(KeyguardManager::class.java) ?: return null
    if (!keyguardManager.isDeviceSecure) return null
    return keyguardManager.createConfirmDeviceCredentialIntent(title, description)
}