// SPDX-License-Identifier: Apache-2.0
package dev.quicktransfer.app.data

import android.content.Context
import android.net.Uri
import android.os.Build
import dev.quicktransfer.app.BuildConfig
import dev.quicktransfer.app.core.TaskLog
import dev.quicktransfer.app.core.TaskLogEntry
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.UUID

internal data class DeviceLogRun(val id: String, val createdAt: Long, val title: String)

/** One append-only private record per operation; only allowlisted fields enter its in-app summary. */
internal class DeviceOperationLogStore(private val context: Context, folder: String, private val heading: String,
    private val explanation: String, private val operations: Map<String, String>, private val titles: Map<String, String>,
    private val summaryFields: List<String>) {
    private val root = File(context.noBackupFilesDir, folder)
    val detailed = DetailedTaskLogStore(root)
    fun events(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9-]{1,80}"))) { "日志编号无效" }
        return File(File(root, id), "events.jsonl")
    }
    fun begin(operation: String, snapshot: JSONObject, id: String = "$operation-${UUID.randomUUID()}"): String {
        detailed.append(id, "operation-start", JSONObject().put("operationId", id).put("operation", operation)
            .put("appVersion", BuildConfig.VERSION_NAME).put("versionCode", BuildConfig.VERSION_CODE)
            .put("sdk", Build.VERSION.SDK_INT).put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL))
        detailed.append(id, "input", snapshot)
        return id
    }
    fun runs(): List<DeviceLogRun> = root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { dir ->
        val file = events(dir.name)
        if (!file.isFile) return@mapNotNull null
        val first = file.bufferedReader().use { it.readLine() }
        try {
            val event = JSONObject(first ?: "")
            DeviceLogRun(dir.name, event.getLong("time"), operations[event.optJSONObject("data")?.optString("operation")] ?: heading)
        } catch (_: JSONException) { DeviceLogRun(dir.name, file.lastModified(), "未完整写入的记录") }
    }.sortedWith(compareByDescending<DeviceLogRun> { it.createdAt }.thenByDescending { it.id })

    fun summary(id: String): TaskLog {
        var result = TaskLog(id, BuildConfig.VERSION_NAME, complete = true)
        events(id).bufferedReader().useLines { lines -> lines.forEach { line ->
            try {
                val entry = JSONObject(line)
                val data = entry.getJSONObject("data")
                val kind = entry.getString("kind")
                if (kind == "operation-start") result = result.copy(appVersion = data.optString("appVersion", BuildConfig.VERSION_NAME))
                val title = titles[kind] ?: return@forEach
                val detail = buildList {
                    for (key in summaryFields) data.opt(key)?.let { add("$key=${it.toString().take(120)}") }
                    data.optJSONObject("error")?.let { add("异常：${it.optString("exceptionClass").take(160)}") }
                    if (data.has("exceptionClass")) add("异常：${data.optString("exceptionClass").take(160)}")
                }.joinToString(" · ")
                result = result.append(TaskLogEntry(entry.getLong("time"), title, detail))
            } catch (_: JSONException) {
                result = result.append(TaskLogEntry(System.currentTimeMillis(), "部分记录尚未完整写入", "可刷新重试；原始内容仍可导出。"))
            }
        } }
        return result
    }
    fun copyTo(id: String, output: OutputStream) {
        val parts = detailed.snapshot(id)
        check(parts.isNotEmpty()) { "没有可导出的日志" }
        output.write("eSIM transfer · $heading\n记录：$id\n$explanation\n".toByteArray())
        for (part in parts) {
            output.write("\n--- ${part.file.name} (${part.length} bytes) ---\n".toByteArray())
            DetailedTaskLogStore.copy(part, output)
        }
    }
    fun export(id: String, uri: Uri) {
        checkNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "无法打开日志保存位置" }.use { copyTo(id, it) }
    }
}
