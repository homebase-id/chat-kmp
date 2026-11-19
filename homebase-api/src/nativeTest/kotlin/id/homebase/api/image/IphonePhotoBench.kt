@file:OptIn(ExperimentalForeignApi::class)

package id.homebase.api.image

import id.homebase.api.file.IOSFileOperationsProvider
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.darwin.KERN_SUCCESS
import platform.darwin.TASK_VM_INFO
import platform.darwin.mach_msg_type_number_tVar
import platform.darwin.mach_task_self_
import platform.darwin.natural_tVar
import platform.darwin.task_info
import platform.darwin.task_vm_info_data_t
import platform.posix.getenv
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.test.Test
import kotlin.time.TimeSource

/**
 * Time and peak memory of real photos through the pipeline, as the app renders them from a file. Skipped unless
 * IMAGE_BENCH_DIR names a folder of HEIC or JPEG photos (pass it to the simulator as SIMCTL_CHILD_IMAGE_BENCH_DIR); the
 * table goes to results-<IMAGE_BENCH_LABEL>.md in that folder. See the PR's test plan for how to run it.
 */
class IphonePhotoBench {
    @OptIn(NativeRuntimeApi::class)
    @Test
    fun measure() {
        val dir = getenv("IMAGE_BENCH_DIR")?.toKString() ?: return
        val label = getenv("IMAGE_BENCH_LABEL")?.toKString() ?: "run"
        val photos = NSFileManager.defaultManager.contentsOfDirectoryAtPath(dir, null).orEmpty()
            .map { it as String }
            .filter { it.substringAfterLast('.').lowercase() in setOf("heic", "heif", "jpg", "jpeg") }
            .sorted()
        val ops = IOSFileOperationsProvider()
        val rows = mutableListOf(
            "| Photo | Source | Setting | Outputs | Time (ms, median of 3) | Peak footprint growth (MB, max of 3) |",
            "|---|---|---|---|---|---|",
        )
        runBlocking(Dispatchers.Default) {
            for (name in photos) for (setting in MediaQuality.entries) {
                val path = "$dir/$name"
                val warmUp = createMediaImages(path, ops, "bench", setting)
                val outputs = (warmUp.thumbnails.map { "${it.pixelWidth}x${it.pixelHeight}" } +
                    listOfNotNull(warmUp.finalImage?.let { "${it.width}x${it.height} final" })).joinToString(", ")
                val samples = List(3) {
                    GC.collect()
                    val base = footprintBytes()
                    var peak = base
                    val sampler = launch { while (isActive) { peak = maxOf(peak, footprintBytes()); delay(1) } }
                    val started = TimeSource.Monotonic.markNow()
                    createMediaImages(path, ops, "bench", setting)
                    val elapsed = started.elapsedNow().inWholeMilliseconds
                    sampler.cancel()
                    peak = maxOf(peak, footprintBytes())
                    elapsed to (peak - base)
                }
                val source = "${warmUp.displayedSize.pixelWidth}x${warmUp.displayedSize.pixelHeight}"
                val time = samples.map { it.first }.sorted()[1]
                val peakMb = samples.maxOf { it.second } / (1024 * 1024)
                rows += "| $name | $source | $setting | $outputs | $time | $peakMb |"
            }
        }
        NSString.create(string = rows.joinToString("\n", postfix = "\n"))
            .writeToFile("$dir/results-$label.md", atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }

    // The process's physical footprint, the number iOS kills apps by.
    private fun footprintBytes(): Long = memScoped {
        val info = alloc<task_vm_info_data_t>()
        val count = alloc<mach_msg_type_number_tVar>()
        count.value = (sizeOf<task_vm_info_data_t>() / sizeOf<natural_tVar>()).convert()
        val result = task_info(mach_task_self_, TASK_VM_INFO.convert(), info.ptr.reinterpret(), count.ptr)
        if (result == KERN_SUCCESS) info.phys_footprint.toLong() else -1L
    }
}
