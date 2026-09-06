package com.godviewer.app.target.diag

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import com.godviewer.app.BuildConfig
import com.godviewer.app.shared.GvLog
import com.godviewer.app.shared.diag.CrashReportProtocol
import com.godviewer.app.shared.diag.DiagBuffer
import com.godviewer.app.shared.diag.DiagReportProtocol
import com.godviewer.app.target.edit.EditMode
import com.godviewer.app.target.hook.ModuleRes
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 目标进程诊断：
 * - GvLog 全量进 DiagBuffer（debug 用，不只闪退）
 * - 启动 / 关键节点：落盘 diag-ring，并 **best-effort 广播到宿主**
 * - 未捕获异常：另落 last-crash + crash 广播
 * - 不吞掉原 handler，系统仍会闪退
 */
object TargetCrashProbe {
    private const val TAG = "Diag"
    private const val DIR_NAME = "godviewer"
    private const val FILE_BOOT = "boot-diag.txt"
    private const val FILE_CRASH = "last-crash.txt"
    private const val FILE_RING = "diag-ring.txt"
    private const val HOST_RECEIVER = "com.godviewer.app.data.HostControlReceiver"
    /** 同一目标进程推全量 ring 的最短间隔，避免 onResume 刷屏 */
    private const val DIAG_PUSH_MIN_INTERVAL_MS = 8_000L

    @Volatile
    private var installed = false

    @Volatile
    private var earlyHandlerInstalled = false

    @Volatile
    private var crashHandledByProbe = false

    @Volatile
    private var lastDiagPushAt = 0L

    @Volatile
    private var lastDiagPushStage = ""

    /**
     * handleLoadPackage 阶段先装一层轻量 handler：
     * Application.onCreate 之前（attachBaseContext / 静态初始化）的闪退也能经
     * GvLog→XposedBridge.log 留痕（LSPosed 管理器日志可见）。
     * [install] 随后用完整探针替换并接回本层；已由完整探针处理过的崩溃不再重复记。
     */
    fun installEarlyHandler() {
        if (earlyHandlerInstalled) return
        earlyHandlerInstalled = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (!crashHandledByProbe) {
                runCatching {
                    GvLog.e(
                        TAG,
                        "FATAL-early thread=${thread.name} ${throwable.javaClass.name}: " +
                            "${throwable.message}\n" +
                            throwable.stackTraceToString().take(3000),
                        throwable,
                    )
                }
            }
            previous?.uncaughtException(thread, throwable)
        }
        GvLog.d(TAG, "early uncaught handler installed")
    }

    fun install(app: Application) {
        if (installed) return
        installed = true
        val pkg = app.packageName
        val process = runCatching {
            if (Build.VERSION.SDK_INT >= 28) {
                Application.getProcessName()
            } else {
                app.applicationInfo.processName
            }
        }.getOrNull() ?: pkg
        DiagBuffer.bindProcess(pkg, process)

        runCatching { installUncaughtHandler(app) }
            .onFailure { GvLog.e(TAG, "install uncaught handler failed", it) }

        runCatching { writeBootSnapshot(app) }
            .onFailure { GvLog.e(TAG, "write boot snapshot failed", it) }
    }

    fun noteStage(app: Context, stage: String, detail: String = "") {
        val msg = if (detail.isBlank()) stage else "$stage $detail"
        GvLog.i(TAG, msg)
        runCatching { flushRing(app) }
        // 关键阶段强制推一次全量 debug ring（不受 interval 限制）
        runCatching { pushDiagToHost(app, stage = stage, force = true) }
    }

    /**
     * 周期性把当前全量 ring 推给宿主（onResume 等）。
     * [force]=false 时受最短间隔约束。
     */
    fun pushDiagSnapshot(app: Context, stage: String, force: Boolean = false) {
        runCatching { flushRing(app) }
        runCatching { pushDiagToHost(app, stage = stage, force = force) }
    }

    private fun installUncaughtHandler(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            crashHandledByProbe = true
            runCatching {
                DiagBuffer.markCrash(thread.name, throwable)
                val crashText = buildCrashFileText(app, thread, throwable)
                writeFile(app, FILE_CRASH, crashText)
                flushRing(app)
                GvLog.e(
                    TAG,
                    "FATAL pkg=${app.packageName} thread=${thread.name} " +
                        "${throwable.javaClass.name}: ${throwable.message}",
                    throwable,
                )
                // 尽量在进程被杀前把摘要送到宿主（失败不影响闪退路径）
                pushToHost(app, thread, throwable, crashText)
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
        GvLog.i(TAG, "uncaught exception probe installed")
    }

    private fun pushToHost(
        app: Application,
        thread: Thread,
        tr: Throwable,
        crashText: String,
    ) {
        runCatching {
            val summary = buildString {
                append(tr.javaClass.name)
                append(": ")
                append(tr.message ?: "")
            }.take(400)
            val stack = (tr.stackTraceToString()).take(CrashReportProtocol.MAX_STACK_CHARS)
            val ring = DiagBuffer.snapshot(headerExtra = stateMap(app))
                .take(CrashReportProtocol.MAX_RING_CHARS)
            val process = DiagBuffer.processName().ifBlank { app.packageName }
            val intent = Intent(CrashReportProtocol.ACTION_TARGET_CRASH).apply {
                component = ComponentName(BuildConfig.PACKAGE_NAME, HOST_RECEIVER)
                putExtra(CrashReportProtocol.EXTRA_TOKEN, CrashReportProtocol.CRASH_TOKEN)
                putExtra(CrashReportProtocol.EXTRA_PACKAGE, app.packageName)
                putExtra(CrashReportProtocol.EXTRA_PROCESS, process)
                putExtra(CrashReportProtocol.EXTRA_THREAD, thread.name)
                putExtra(CrashReportProtocol.EXTRA_SUMMARY, summary)
                putExtra(CrashReportProtocol.EXTRA_STACK, stack)
                putExtra(CrashReportProtocol.EXTRA_RING, ring)
                putExtra(CrashReportProtocol.EXTRA_TIME, System.currentTimeMillis())
                putExtra(CrashReportProtocol.EXTRA_MODULE_VC, BuildConfig.VERSION_CODE)
                putExtra(CrashReportProtocol.EXTRA_MODULE_VN, BuildConfig.VERSION_NAME)
                // 完整落盘文案已写 last-crash.txt；宿主以 stack/ring 为准，无需重复字段
            }
            app.applicationContext.sendBroadcast(intent)
            GvLog.i(TAG, "crash report broadcast sent to host")
        }.onFailure {
            GvLog.w(TAG, "crash report broadcast failed", it)
        }
    }

    private fun writeBootSnapshot(app: Application) {
        val text = buildSnapshot(app, kind = "boot")
        writeFile(app, FILE_BOOT, text)
        flushRing(app)
        GvLog.i(TAG, "boot diag written filesDir/$DIR_NAME/$FILE_BOOT")
        GvLog.i(TAG, summarizeState(app))
        // 启动后立刻把全量 ring 推宿主，方便未闪退也能在设置里复制
        pushDiagToHost(app, stage = "boot", force = true)
    }

    private fun pushDiagToHost(app: Context, stage: String, force: Boolean) {
        runCatching {
            val now = System.currentTimeMillis()
            if (!force) {
                val sameStage = stage == lastDiagPushStage
                if (sameStage && now - lastDiagPushAt < DIAG_PUSH_MIN_INTERVAL_MS) return
                if (!sameStage && now - lastDiagPushAt < 2_500L) return
            }
            val application = app.applicationContext
            val process = DiagBuffer.processName().ifBlank { application.packageName }
            val body = DiagBuffer.snapshot(
                headerExtra = stateMap(application) + mapOf("stage" to stage),
            ).take(DiagReportProtocol.MAX_BODY_CHARS)
            if (body.isBlank()) return
            val intent = Intent(DiagReportProtocol.ACTION_TARGET_DIAG).apply {
                component = ComponentName(BuildConfig.PACKAGE_NAME, HOST_RECEIVER)
                putExtra(DiagReportProtocol.EXTRA_TOKEN, DiagReportProtocol.DIAG_TOKEN)
                putExtra(DiagReportProtocol.EXTRA_PACKAGE, application.packageName)
                putExtra(DiagReportProtocol.EXTRA_PROCESS, process)
                putExtra(DiagReportProtocol.EXTRA_STAGE, stage.take(120))
                putExtra(DiagReportProtocol.EXTRA_BODY, body)
                putExtra(DiagReportProtocol.EXTRA_TIME, now)
                putExtra(DiagReportProtocol.EXTRA_MODULE_VC, BuildConfig.VERSION_CODE)
                putExtra(DiagReportProtocol.EXTRA_MODULE_VN, BuildConfig.VERSION_NAME)
                putExtra(DiagReportProtocol.EXTRA_LINE_COUNT, DiagBuffer.size())
            }
            application.sendBroadcast(intent)
            lastDiagPushAt = now
            lastDiagPushStage = stage
            // 不经 GvLog，避免推送本身再灌进 ring 造成回声
        }.onFailure {
            // 静默：推送失败不得拖垮目标
        }
    }

    private fun buildCrashFileText(app: Application, thread: Thread, tr: Throwable): String {
        return buildString {
            append(buildSnapshot(app, kind = "crash"))
            appendLine()
            appendLine("--- throwable ---")
            appendLine("thread=${thread.name}")
            appendLine(tr.stackTraceToString())
        }
    }

    private fun flushRing(app: Context) {
        writeFile(app, FILE_RING, DiagBuffer.snapshot(headerExtra = stateMap(app)))
    }

    private fun buildSnapshot(app: Context, kind: String): String {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())
        return DiagBuffer.snapshot(
            headerExtra = stateMap(app) + mapOf(
                "kind" to kind,
                "time" to ts,
            ),
        )
    }

    private fun stateMap(app: Context): Map<String, String> {
        return mapOf(
            "moduleResReady" to ModuleRes.isModuleResReady().toString(),
            "editEnabled" to runCatching { EditMode.isEnabled() }.getOrDefault(false).toString(),
            "targetSdk" to (app.applicationInfo?.targetSdkVersion?.toString() ?: "?"),
            "appData" to (app.applicationInfo?.dataDir ?: "?"),
        )
    }

    private fun summarizeState(app: Context): String {
        val m = stateMap(app)
        return "state moduleRes=${m["moduleResReady"]} edit=${m["editEnabled"]} " +
            "sdk=${Build.VERSION.SDK_INT} targetSdk=${m["targetSdk"]} " +
            "vc=${BuildConfig.VERSION_CODE} vn=${BuildConfig.VERSION_NAME}"
    }

    private fun writeFile(app: Context, name: String, content: String) {
        runCatching {
            val dir = File(app.filesDir, DIR_NAME)
            if (!dir.exists()) dir.mkdirs()
            File(dir, name).writeText(content)
        }.onFailure {
            GvLog.w(TAG, "write $name failed", it)
        }
    }
}
