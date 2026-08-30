package com.pagebinder.app.ui

import java.util.Locale

fun formatStorageBytes(bytes: Long): String =
    when {
        bytes < 1_024 -> "$bytes B"
        bytes < 1_048_576 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1_024.0)
        bytes < 1_073_741_824 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1_048_576.0)
        else -> String.format(Locale.getDefault(), "%.1f GB", bytes / 1_073_741_824.0)
    }
