@file:OptIn(ExperimentalForeignApi::class)

package id.homebase.api.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.darwin.KERN_SUCCESS
import platform.darwin.TASK_VM_INFO
import platform.darwin.mach_msg_type_number_tVar
import platform.darwin.mach_task_self_
import platform.darwin.natural_tVar
import platform.darwin.task_info
import platform.darwin.task_vm_info_data_t

/** The process's physical footprint, the number iOS kills an app by; 0 if the kernel won't say. */
fun processFootprintBytes(): Long = memScoped {
    val info = alloc<task_vm_info_data_t>()
    val count = alloc<mach_msg_type_number_tVar>()
    count.value = (sizeOf<task_vm_info_data_t>() / sizeOf<natural_tVar>()).convert()
    val result = task_info(mach_task_self_, TASK_VM_INFO.convert(), info.ptr.reinterpret(), count.ptr)
    if (result == KERN_SUCCESS) info.phys_footprint.toLong() else 0L
}
