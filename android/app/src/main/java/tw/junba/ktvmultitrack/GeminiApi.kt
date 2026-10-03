package tw.junba.ktvmultitrack

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object GeminiApi {
    private val fallbackModels = listOf("gemini-3.8-flash", "gemini-3.7-flash", "gemini-3.6-flash")
    private val retrySeconds = listOf(2L, 5L, 10L)

    private class HttpStatusException(val code: Int, val payload: String) :
        RuntimeException("HTTP $code: $payload")

    private fun readBody(c: HttpURLConnection, ok: Boolean): String {
        val stream = if (ok) c.inputStream else c.errorStream
        return stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    }

    private fun postJsonOnce(url: String, body: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 30_000
        c.readTimeout = 180_000
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val text = readBody(c, code in 200..299)
        if (code !in 200..299) throw HttpStatusException(code, text)
        return text
    }

    private fun modelOrder(primary: String): List<String> {
        val out = mutableListOf<String>()
        (listOf(primary) + fallbackModels).forEach { if (it.isNotBlank() && it !in out) out += it }
        return out
    }

    private fun generateWithFallback(
        key: String,
        primary: String,
        req: JSONObject,
        label: String,
        onStatus: ((String) -> Unit)? = null
    ): Pair<String, String> {
        var last: Exception? = null
        val models = modelOrder(primary)
        for ((mi, model) in models.withIndex()) {
            for (attempt in retrySeconds.indices) {
                try {
                    onStatus?.invoke("$label｜$model｜嘗試 ${attempt + 1}/${retrySeconds.size}")
                    val body = postJsonOnce(
                        "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key",
                        req.toString()
                    )
                    return body to model
                } catch (e: HttpStatusException) {
                    last = e
                    if (e.code == 404) {
                        if (mi < models.lastIndex) onStatus?.invoke("$model 暫不可用，切換備援模型…")
                        break
                    }
                    if (e.code == 429 || e.code in 500..599) {
                        if (attempt < retrySeconds.lastIndex) {
                            val sec = retrySeconds[attempt]
                            onStatus?.invoke("Gemini 忙碌中｜$sec 秒後自動重試 ${attempt + 2}/${retrySeconds.size}")
                            Thread.sleep(sec * 1000)
                            continue
                        }
                        if (mi < models.lastIndex) onStatus?.invoke("$model 仍忙碌，切換備援模型…")
                        break
                    }
                    if (e.code == 401 || e.code == 403) {
                        throw RuntimeException("Gemini API Key 無效、權限不足或尚未啟用服務。")
                    }
                    throw e
                } catch (e: Exception) {
                    last = e
                    if (attempt < retrySeconds.lastIndex) {
                        val sec = retrySeconds[attempt]
                        onStatus?.invoke("Gemini 連線暫時失敗｜$sec 秒後重試…")
                        Thread.sleep(sec * 1000)
                        continue
                    }
                    break
                }
            }
        }
        val msg = last?.message.orEmpty()
        throw RuntimeException("Gemini 目前高負載或暫時無法使用；原始文字與 KTV 不受影響，可稍後再試。${if (msg.isNotBlank()) "\n$msg" else ""}")
    }

    private fun responseText(json: String): String {
        val o = JSONObject(json)
        val parts = o.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
        return parts.getJSONObject(0).optString("text", "")
    }

    private fun jsonArrayFromText(s: String): JSONArray {
        var x = s.trim()
            .replace(Regex("^```(?:json)?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*```$"), "")
        val a = x.indexOf('[')
        val b = x.lastIndexOf(']')
        if (a >= 0 && b > a) x = x.substring(a, b + 1)
        return JSONArray(x)
    }

    fun polish(
        track: TextTrack,
        key: String,
        model: String,
        onStatus: ((String) -> Unit)? = null
    ): TextTrack {
        val out = mutableListOf<Segment>()
        var usedModel = model
        val batch = 24
        for (off in track.segments.indices step batch) {
            val part = track.segments.subList(off, minOf(off + batch, track.segments.size))
            val arr = JSONArray()
            part.forEachIndexed { i, s ->
                arr.put(JSONObject().put("id", i).put("speaker", s.speaker).put("text", s.text))
            }
            val prompt = "你是繁體中文會議逐字稿校稿助理。只回傳 JSON 陣列 " +
                "[{\\\"id\\\":0,\\\"text\\\":\\\"...\\\"}]。使用台灣繁體中文，修正錯字與口語贅詞但不得新增事實，保留每個 id。輸入：$arr"
            val req = JSONObject().put(
                "contents",
                JSONArray().put(
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                )
            )
            val (raw, used) = generateWithFallback(key, model, req, "Gemini 順稿", onStatus)
            usedModel = used
            val got = jsonArrayFromText(responseText(raw))
            val map = mutableMapOf<Int, String>()
            for (i in 0 until got.length()) {
                val o = got.optJSONObject(i) ?: continue
                map[o.optInt("id", -1)] = o.optString("text", "")
            }
            part.forEachIndexed { i, s -> out += s.copy(text = map[i]?.ifBlank { s.text } ?: s.text) }
        }
        return TextTrack(track.name + "｜Gemini順稿", out, "Gemini $usedModel", track.timed, track.estimated)
    }

    private fun size(cr: ContentResolver, u: Uri): Long {
        cr.query(u, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val v = c.getLong(0)
                if (v > 0) return v
            }
        }
        cr.openAssetFileDescriptor(u, "r")?.use { afd -> if (afd.length > 0) return afd.length }
        return -1
    }

    private fun mime(cr: ContentResolver, u: Uri) = cr.getType(u) ?: "audio/mpeg"

    private fun uploadOnce(cr: ContentResolver, u: Uri, key: String): Pair<String, String> {
        val len = size(cr, u)
        if (len <= 0) throw RuntimeException("無法取得音檔大小")
        val mt = mime(cr, u)
        val start = URL("https://generativelanguage.googleapis.com/upload/v1beta/files?key=$key").openConnection() as HttpURLConnection
        start.requestMethod = "POST"
        start.doOutput = true
        start.connectTimeout = 30_000
        start.readTimeout = 60_000
        start.setRequestProperty("X-Goog-Upload-Protocol", "resumable")
        start.setRequestProperty("X-Goog-Upload-Command", "start")
        start.setRequestProperty("X-Goog-Upload-Header-Content-Length", len.toString())
        start.setRequestProperty("X-Goog-Upload-Header-Content-Type", mt)
        start.setRequestProperty("Content-Type", "application/json")
        start.outputStream.use { it.write("{\"file\":{\"display_name\":\"junba_review_audio\"}}".toByteArray()) }
        val startCode = start.responseCode
        if (startCode !in 200..299) throw HttpStatusException(startCode, readBody(start, false))
        val uploadUrl = start.getHeaderField("X-Goog-Upload-URL") ?: throw RuntimeException("Gemini 未回傳上傳網址")

        val up = URL(uploadUrl).openConnection() as HttpURLConnection
        up.requestMethod = "POST"
        up.doOutput = true
        up.connectTimeout = 30_000
        up.readTimeout = 180_000
        up.setFixedLengthStreamingMode(len)
        up.setRequestProperty("Content-Type", mt)
        up.setRequestProperty("X-Goog-Upload-Offset", "0")
        up.setRequestProperty("X-Goog-Upload-Command", "upload, finalize")
        cr.openInputStream(u)?.use { input -> up.outputStream.use { output -> input.copyTo(output) } }
            ?: throw RuntimeException("無法讀取音檔")
        val code = up.responseCode
        val txt = readBody(up, code in 200..299)
        if (code !in 200..299) throw HttpStatusException(code, txt)
        val file = JSONObject(txt).optJSONObject("file") ?: JSONObject(txt)
        return file.getString("uri") to file.optString("mimeType", mt)
    }

    private fun upload(
        cr: ContentResolver,
        u: Uri,
        key: String,
        onStatus: ((String) -> Unit)? = null
    ): Pair<String, String> {
        var last: Exception? = null
        for (attempt in retrySeconds.indices) {
            try {
                onStatus?.invoke("上傳錄音至 Gemini｜嘗試 ${attempt + 1}/${retrySeconds.size}")
                return uploadOnce(cr, u, key)
            } catch (e: HttpStatusException) {
                last = e
                if ((e.code == 429 || e.code in 500..599) && attempt < retrySeconds.lastIndex) {
                    val sec = retrySeconds[attempt]
                    onStatus?.invoke("Gemini 上傳服務忙碌｜$sec 秒後自動重試…")
                    Thread.sleep(sec * 1000)
                    continue
                }
                if (e.code == 401 || e.code == 403) throw RuntimeException("Gemini API Key 無效或權限不足。")
                throw e
            } catch (e: Exception) {
                last = e
                if (attempt < retrySeconds.lastIndex) {
                    val sec = retrySeconds[attempt]
                    onStatus?.invoke("音訊上傳暫時失敗｜$sec 秒後重試…")
                    Thread.sleep(sec * 1000)
                    continue
                }
            }
        }
        throw RuntimeException("Gemini 音訊上傳暫時失敗；KTV 與原稿不受影響。${last?.message.orEmpty()}")
    }

    fun transcribeForReview(
        cr: ContentResolver,
        u: Uri,
        key: String,
        model: String,
        onStatus: ((String) -> Unit)? = null
    ): TextTrack {
        val (uri, mt) = upload(cr, u, key, onStatus)
        val prompt = "請完整聽錄音並建立核對逐字稿。辨識不同講者為講者1、講者2等，不猜姓名。" +
            "每段輸出 start 秒數、end 秒數、speaker、text。中文使用繁體中文台灣用語。" +
            "只輸出 JSON 陣列，不要 markdown。"
        val parts = JSONArray()
            .put(JSONObject().put("file_data", JSONObject().put("mime_type", mt).put("file_uri", uri)))
            .put(JSONObject().put("text", prompt))
        val req = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", parts)))
        val (raw, used) = generateWithFallback(key, model, req, "Gemini 聽錄音核對", onStatus)
        val arr = jsonArrayFromText(responseText(raw))
        val segs = mutableListOf<Segment>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val tx = o.optString("text", "")
            if (tx.isNotBlank()) {
                segs += Segment(
                    o.optDouble("start", 0.0),
                    o.optDouble("end", o.optDouble("start", 0.0)),
                    tx,
                    o.optString("speaker", "")
                )
            }
        }
        if (segs.isEmpty()) throw RuntimeException("Gemini 沒有回傳可用的時間軸逐字稿。")
        return TextTrack("Gemini智慧核對", segs, "Gemini Audio $used", true, false)
    }
}
