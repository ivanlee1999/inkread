package me.ash.reader.performance

import android.os.Debug
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** Diagnostic-only observer. Never enabled in timing or publication-hash runs. */
internal class ResourceDiagnostic {
    private val actions = JSONArray()
    private val idle = JSONArray()

    data class Point(
        val elapsedNs: Long,
        val epochMs: Long,
        val threadCpuNs: Long,
        val processCpuMs: Long,
        val javaBytes: Long,
        val nativeBytes: Long,
        val counters: Map<String, Long>,
        val captureNs: Long,
    ) {
        fun json() = JSONObject().apply {
            put("elapsed_realtime_ns", elapsedNs); put("epoch_ms", epochMs)
            put("main_thread_cpu_ns", threadCpuNs); put("app_process_cpu_ms", processCpuMs)
            put("java_heap_bytes", javaBytes); put("native_heap_bytes", nativeBytes)
            put("art_counters", JSONObject(counters)); put("counter_capture_wall_ns", captureNs)
        }
    }

    private fun point(): Point {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Diagnostic thread CPU must be main-thread CPU" }
        val start = SystemClock.elapsedRealtimeNanos()
        val epoch = System.currentTimeMillis()
        val threadCpu = Debug.threadCpuTimeNanos()
        check(threadCpu >= 0) { "Main-thread CPU time is unavailable on this runtime" }
        val processCpu = Process.getElapsedCpuTime()
        val stats = Debug.getRuntimeStats()
        val counters = COUNTERS.associateWith { key ->
            checkNotNull(stats[key]?.toLongOrNull()) { "Missing numeric ART runtime counter: $key" }
        }
        val runtime = Runtime.getRuntime()
        val javaBytes = runtime.totalMemory() - runtime.freeMemory()
        val nativeBytes = Debug.getNativeHeapAllocatedSize()
        return Point(start, epoch, threadCpu, processCpu, javaBytes, nativeBytes, counters,
            SystemClock.elapsedRealtimeNanos() - start)
    }

    fun begin(name: String, iteration: Int, warmup: Boolean): Point {
        val before = point()
        marker("begin", name, iteration, warmup, before)
        return before
    }

    fun end(name: String, iteration: Int, warmup: Boolean, before: Point,
            actionStartNs: Long, actionReturnedNs: Long, readinessMetNs: Long, renderBarriersDoneNs: Long) {
        val after = point()
        marker("end", name, iteration, warmup, after)
        val deltas = COUNTERS.associateWith { key ->
            (after.counters.getValue(key) - before.counters.getValue(key)).also {
                check(it >= 0) { "Nonmonotonic ART runtime counter: $key" }
            }
        }
        actions.put(JSONObject().apply {
            put("scenario", name); put("iteration", iteration); put("warmup", warmup)
            put("before", before.json()); put("after", after.json())
            put("phase_elapsed_realtime_ns", JSONObject().apply {
                put("action_start", actionStartNs); put("action_returned", actionReturnedNs)
                put("readiness_met", readinessMetNs); put("render_barriers_done", renderBarriersDoneNs)
            })
            put("art_counter_deltas", JSONObject(deltas))
            put("main_thread_cpu_ns_delta", after.threadCpuNs - before.threadCpuNs)
            put("app_process_cpu_ms_delta", after.processCpuMs - before.processCpuMs)
        })
    }

    /** Equal elapsed idle checkpoints, not a claim of steady state. No explicit collection. */
    suspend fun finish() = withTimeout(15_000) {
        val idleOrigin = SystemClock.elapsedRealtimeNanos()
        for (targetMs in listOf(0L, 2_000L, 5_000L)) {
            val remainingNs = idleOrigin + targetMs * 1_000_000 - SystemClock.elapsedRealtimeNanos()
            if (remainingNs > 0) delay((remainingNs + 999_999) / 1_000_000)
            val before = point()
            val memory = Debug.MemoryInfo()
            // Unlike dumpsys meminfo, this self-process query does not explicitly request GC.
            Debug.getMemoryInfo(memory)
            val after = point()
            idle.put(JSONObject().apply {
                put("target_idle_ms", targetMs)
                put("actual_idle_ms", (before.elapsedNs - idleOrigin) / 1_000_000.0)
                put("before_memory_query", before.json()); put("after_memory_query", after.json())
                put("memory_query_wall_ms", (after.elapsedNs - before.elapsedNs) / 1_000_000.0)
                put("pss_kib", memory.totalPss); put("dalvik_pss_kib", memory.dalvikPss)
                put("native_pss_kib", memory.nativePss); put("other_pss_kib", memory.otherPss)
                put("private_dirty_kib", memory.totalPrivateDirty)
                put("private_clean_kib", memory.totalPrivateClean)
                put("memory_summary", JSONObject(memory.memoryStats))
            })
            Log.i(TAG, "idle target_ms=$targetMs elapsed_ns=${after.elapsedNs} epoch_ms=${after.epochMs}")
        }
    }

    fun json() = JSONObject().apply {
        put("version", VERSION); put("pid", Process.myPid()); put("main_thread_tid", Process.myTid())
        put("forced_gc", false); put("inter_action_idle_ms", 0)
        put("actions", actions); put("idle_snapshots", idle)
        put("warning", "Diagnostic only: approximate process-wide ART counters and observer allocations; " +
            "counter capture/logging can alter GC scheduling. Main-thread CPU includes observer overhead. " +
            "Blocking-GC counters are not a direct total of main-thread allocation waits. " +
            "Fixed idle does not establish steady state. App process excludes WebView renderer.")
    }

    private fun marker(boundary: String, name: String, iteration: Int, warmup: Boolean, point: Point) {
        Log.i(TAG, "$boundary $name iteration=$iteration warmup=$warmup " +
            "elapsed_ns=${point.elapsedNs} epoch_ms=${point.epochMs}")
    }

    companion object {
        const val VERSION = "inkread-resource-diagnostic-v2"
        const val TAG = "InkReadResource"
        val COUNTERS = listOf("art.gc.bytes-allocated", "art.gc.bytes-freed", "art.gc.gc-count",
            "art.gc.gc-time", "art.gc.blocking-gc-count", "art.gc.blocking-gc-time")
    }
}
