package dev.nutting.pocketllm.util

import android.app.ActivityManager
import android.content.Context

fun deviceTotalRamMb(context: Context): Int {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memInfo = ActivityManager.MemoryInfo()
    am.getMemoryInfo(memInfo)
    return (memInfo.totalMem / (1024 * 1024)).toInt()
}
