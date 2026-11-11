package id.homebase.api.client

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_RCVBUF
import platform.posix.SO_REUSEADDR
import platform.posix.accept
import platform.posix.bind
import platform.posix.getsockname
import platform.posix.listen
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar
import kotlin.concurrent.AtomicInt
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker

@OptIn(ExperimentalForeignApi::class)
actual class StallingServer actual constructor() : AutoCloseable {
    private val listenFd = socket(AF_INET, SOCK_STREAM, 0).also { check(it >= 0) { "socket() failed" } }
    private val acceptedFd = AtomicInt(-1)
    private val worker = Worker.start()
    actual val port: Int

    init {
        port = memScoped {
            val one = alloc<IntVar>().apply { value = 1 }
            setsockopt(listenFd, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().convert())
            val small = alloc<IntVar>().apply { value = 16 * 1024 }
            setsockopt(listenFd, SOL_SOCKET, SO_RCVBUF, small.ptr, sizeOf<IntVar>().convert())
            val addr = alloc<sockaddr_in>().apply {
                sin_len = sizeOf<sockaddr_in>().convert()
                sin_family = AF_INET.convert()
                sin_port = 0u
                sin_addr.s_addr = 0x0100007Fu // 127.0.0.1, network byte order
            }
            check(bind(listenFd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0) { "bind() failed" }
            check(listen(listenFd, 1) == 0) { "listen() failed" }
            val len = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in>().convert() }
            getsockname(listenFd, addr.ptr.reinterpret(), len.ptr)
            val p = addr.sin_port.toInt()
            ((p and 0xff) shl 8) or ((p shr 8) and 0xff)
        }
        val fd = listenFd
        val out = acceptedFd
        worker.execute(TransferMode.SAFE, { fd to out }) { (l, o) -> o.value = accept(l, null, null) }
    }

    actual override fun close() {
        acceptedFd.value.takeIf { it >= 0 }?.let { platform.posix.close(it) }
        platform.posix.close(listenFd)
        worker.requestTermination(processScheduledJobs = false)
    }
}
