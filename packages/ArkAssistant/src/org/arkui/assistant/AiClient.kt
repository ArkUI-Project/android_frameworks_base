/* Copyright (C) 2026 The ArkUI Project; SPDX-License-Identifier: Apache-2.0 */
package org.arkui.assistant

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.CancellationException
import org.json.JSONArray
import org.json.JSONObject

internal class AiClient(private val config: AssistantConfig) {
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var cancelled = false

    fun cancel() { cancelled = true; connection?.disconnect() }

    fun complete(messages: JSONArray, tools: JSONArray?): JSONObject {
        val body = JSONObject().put("model", config.model).put("messages", messages)
            .put("max_tokens", 1600).put("stream", false)
        if (tools != null && tools.length() > 0) body.put("tools", tools).put("tool_choice", "auto")
        if (URI(config.endpoint).host == "api.deepseek.com") {
            body.put("thinking", JSONObject().put("type", "disabled"))
        }
        var attempt = 0
        while (true) {
            if (cancelled || Thread.currentThread().isInterrupted) throw CancellationException()
            val request = URI(config.completionsUrl()).toURL().openConnection() as HttpURLConnection
            connection = request
            try {
                request.requestMethod = "POST"
                request.instanceFollowRedirects = false
                request.connectTimeout = 15000
                request.readTimeout = 60000
                request.doOutput = true
                request.setRequestProperty("Content-Type", "application/json")
                request.setRequestProperty("Authorization", "Bearer " + config.key)
                request.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val status = request.responseCode
                if ((status == 429 || status == 502 || status == 503) && attempt++ < 2) {
                    Thread.sleep(1000L * attempt)
                    continue
                }
                if (status !in 200..299) throw IOException("AI service returned HTTP $status")
                val bytes = request.inputStream.use { it.readNBytes(2 * 1024 * 1024 + 1) }
                if (bytes.size > 2 * 1024 * 1024) throw IOException("AI response is too large")
                if (cancelled) throw CancellationException()
                val message = JSONObject(String(bytes, Charsets.UTF_8)).getJSONArray("choices")
                    .getJSONObject(0).getJSONObject("message")
                if (message.optString("role") != "assistant") throw IOException("Invalid AI response")
                return message
            } finally {
                request.disconnect()
                if (connection === request) connection = null
            }
        }
    }

    companion object {
        private fun tool(name: String, description: String, properties: JSONObject = JSONObject(),
            required: List<String> = emptyList()): JSONObject = JSONObject().put("type", "function")
            .put("function", JSONObject().put("name", name).put("description", description)
                .put("parameters", JSONObject().put("type", "object").put("properties", properties)
                    .put("required", JSONArray(required)).put("additionalProperties", false)))

        private fun field(type: String, description: String) = JSONObject().put("type", type).put("description", description)
        fun tools(control: Boolean): JSONArray {
            val tools = JSONArray().put(tool("capture", "Read a fresh screenshot of the current screen."))
                .put(tool("list_apps", "List launchable apps and their package names."))
                .put(tool("wait", "Wait for a page to load, then get a fresh screenshot.",
                    JSONObject().put("milliseconds", field("integer", "100 to 3000 milliseconds")), listOf("milliseconds")))
            if (!control) return tools
            val point = JSONObject().put("x", field("integer", "Horizontal coordinate, 0 to 1000"))
                .put("y", field("integer", "Vertical coordinate, 0 to 1000"))
            tools.put(tool("tap", "Tap the current screen. Use coordinates from the most recent screenshot.", point, listOf("x", "y")))
                .put(tool("long_press", "Touch and hold for 650 ms.", JSONObject(point.toString()), listOf("x", "y")))
                .put(tool("swipe", "Swipe from (x,y) to (end_x,end_y). All coordinates are 0 to 1000.",
                    JSONObject(point.toString()).put("end_x", field("integer", "End horizontal coordinate"))
                        .put("end_y", field("integer", "End vertical coordinate")), listOf("x", "y", "end_x", "end_y")))
                .put(tool("type_text", "Paste Unicode text into the focused field. Tap the field first.",
                    JSONObject().put("text", field("string", "Text to insert, at most 4000 characters")), listOf("text")))
                .put(tool("key", "Press a navigation key: back, home, recents or enter.",
                    JSONObject().put("key", field("string", "back, home, recents or enter").put("enum", JSONArray(listOf("back", "home", "recents", "enter")))), listOf("key")))
                .put(tool("open_app", "Open an app using a package name returned by list_apps.",
                    JSONObject().put("package", field("string", "Installed app package name")), listOf("package")))
                .put(tool("open_url", "Open an HTTPS webpage or search URL in the browser.",
                    JSONObject().put("url", field("string", "HTTPS URL")), listOf("url")))
                .put(tool("request_confirmation", "Pause for the user's explicit confirmation before sending, submitting, purchasing, deleting or changing security settings.",
                    JSONObject().put("message", field("string", "Explain the exact operation in the user's language")), listOf("message")))
            return tools
        }
    }
}
