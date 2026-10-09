package dev.nutting.pocketllm.ui.modelmanagement

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
fun CellularWarningDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download on Cellular?") },
        text = { Text("You're not on Wi-Fi. This download may use significant mobile data. Continue?") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Download Anyway")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
