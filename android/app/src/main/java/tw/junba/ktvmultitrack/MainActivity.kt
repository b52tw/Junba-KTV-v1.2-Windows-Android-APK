package tw.junba.ktvmultitrack

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.method.PasswordTransformationMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val tracks = mutableListOf<TextTrack>()
    private var audioUri: Uri? = null
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var fullText: TextView
    private lateinit var fullScroll: ScrollView
    private lateinit var timeline: ListView
    private lateinit var activeSpinner: Spinner
    private lateinit var compareSpinner: Spinner
    private lateinit var seek: SeekBar
    private lateinit var timeLabel: TextView
    private lateinit var summary: TextView
    private lateinit var audioLabel: TextView
    private lateinit var aiStatus: TextView

    private var ranges = mutableListOf<Pair<Int, Int>>()
    private var review = listOf<ReviewResult>()
    private var currentIndex = -1
    private var plainFullText = ""
    private var aiBusy = false
    private var playbackSpeed = 1.0f

    private val prefs by lazy { getSharedPreferences("junba_ktv", MODE_PRIVATE) }

    companion object {
        const val REQ_AUDIO = 101
        const val REQ_TRACK = 102
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "峻爸 KTV 多文字軌核對器 v1.2a"
        setContentView(buildUi())
        handler.post(ticker)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun button(textValue: String, action: () -> Unit): Button =
        Button(this).apply {
            text = textValue
            setOnClickListener { action() }
            isAllCaps = false
        }

    private fun horizontalScrollable(row: LinearLayout): HorizontalScrollView =
        HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        val titleView = TextView(this).apply {
            text = "峻爸 KTV 多文字軌核對器 v1.2a｜KTV 閱讀優先"
            textSize = 20f
            setTextColor(Color.rgb(25, 80, 150))
            setPadding(0, 0, 0, dp(6))
        }
        root.addView(titleView)

        // Audio path stays one compact line.
        val audioRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        audioLabel = TextView(this).apply {
            text = "尚未選擇錄音檔"
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            gravity = Gravity.CENTER_VERTICAL
        }
        audioRow.addView(audioLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        audioRow.addView(button("選擇錄音") { openAudio() })
        root.addView(audioRow)

        // Playback controls always stay visible; horizontal scroll avoids squeezing on small phones.
        val playerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        playerRow.addView(button("▶") { play() })
        playerRow.addView(button("⏸") { player?.pause() })
        playerRow.addView(button("⏹") { stop() })
        playerRow.addView(button("↶5秒") { seekDelta(-5000) })
        playerRow.addView(button("5秒↷") { seekDelta(5000) })
        timeLabel = TextView(this).apply {
            text = "00:00:00 / 00:00:00"
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        playerRow.addView(timeLabel)
        val speed = Spinner(this)
        val speeds = listOf("0.75x", "1.0x", "1.25x", "1.5x", "2.0x")
        speed.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, speeds)
        speed.setSelection(1)
        speed.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                playbackSpeed = listOf(.75f, 1f, 1.25f, 1.5f, 2f)[pos]
                if (Build.VERSION.SDK_INT >= 23) {
                    player?.let { mp ->
                        try {
                            val params = mp.playbackParams
                            params.speed = playbackSpeed
                            mp.playbackParams = params
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }
        playerRow.addView(speed)
        root.addView(horizontalScrollable(playerRow))

        seek = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val d = player?.duration ?: 0
                        if (d > 0) player?.seekTo((d * progress / 1000.0).toInt())
                    }
                }

                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        }
        root.addView(seek)

        // Track selectors stay visible, management and AI open only when needed.
        val selectRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        activeSpinner = Spinner(this)
        compareSpinner = Spinner(this)
        selectRow.addView(TextView(this).apply { text = "KTV"; gravity = Gravity.CENTER_VERTICAL })
        selectRow.addView(activeSpinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        selectRow.addView(TextView(this).apply { text = "比較"; gravity = Gravity.CENTER_VERTICAL })
        selectRow.addView(compareSpinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(selectRow)

        val toolRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        toolRow.addView(button("☰ 文字軌") { showTrackManager() })
        toolRow.addView(button("✨ AI 工具") { showAiTools() })
        summary = TextView(this).apply {
            text = "尚無文字軌"
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(6), 0)
        }
        toolRow.addView(summary, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        aiStatus = TextView(this).apply {
            text = "AI：待命"
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.GRAY)
        }
        toolRow.addView(aiStatus)
        root.addView(toolRow)

        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                rebuild()
            }
        }
        activeSpinner.onItemSelectedListener = listener
        compareSpinner.onItemSelectedListener = listener

        // Main reading area gets almost all remaining height.
        fullText = TextView(this).apply {
            textSize = 19f
            setTextColor(Color.DKGRAY)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setTextIsSelectable(true)
        }
        fullScroll = ScrollView(this).apply {
            addView(fullText)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f)
        }
        root.addView(fullScroll)

        timeline = ListView(this).apply {
            dividerHeight = 1
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.15f)
        }
        root.addView(timeline)
        timeline.setOnItemClickListener { _, _, pos, _ ->
            val t = activeTrack() ?: return@setOnItemClickListener
            player?.seekTo((t.segments[pos].start * 1000).toInt())
            play()
        }

        return root
    }

    // ---------- Audio / track file pickers ----------
    private fun openAudio() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "audio/*"
            },
            REQ_AUDIO
        )
    }

    private fun openTrack() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            },
            REQ_TRACK
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (res != RESULT_OK || data == null) return
        if (req == REQ_AUDIO) {
            data.data?.let { uri ->
                audioUri = uri
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {
                }
                audioLabel.text = TrackParser.displayName(contentResolver, uri)
                preparePlayer(uri)
            }
        }
        if (req == REQ_TRACK) {
            val uris = mutableListOf<Uri>()
            data.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) uris += clip.getItemAt(i).uri
            } ?: data.data?.let { uris += it }
            val oldActive = activeSpinner.selectedItemPosition
            val oldCompare = compareSpinner.selectedItemPosition - 1
            uris.forEach { uri ->
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {
                }
                try {
                    tracks += TrackParser.load(contentResolver, uri)
                } catch (e: Exception) {
                    toast("讀取失敗：${e.message}")
                }
            }
            autoAlign()
            refreshSpinners(oldActive, oldCompare)
        }
    }

    private fun preparePlayer(uri: Uri) {
        player?.release()
        player = MediaPlayer().apply {
            setDataSource(this@MainActivity, uri)
            setOnPreparedListener {
                if (Build.VERSION.SDK_INT >= 23) {
                    try {
                        val params = playbackParams
                        params.speed = playbackSpeed
                        playbackParams = params
                    } catch (_: Exception) {
                    }
                }
                autoAlign()
                updateViews()
            }
            setOnErrorListener { _, _, _ ->
                toast("音訊無法播放")
                true
            }
            prepareAsync()
        }
    }

    private fun play() {
        val p = player
        if (p == null) {
            toast("請先選擇錄音")
            return
        }
        p.start()
    }

    private fun stop() {
        player?.pause()
        player?.seekTo(0)
        updateViews()
    }

    private fun seekDelta(ms: Int) {
        player?.let { it.seekTo((it.currentPosition + ms).coerceIn(0, it.duration)) }
    }

    // ---------- Track management dialog ----------
    private fun showTrackManager() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("＋ 加入文字／字幕") { openTrack() })
        row.addView(button("自動對齊") { autoAlign(); refreshSpinners() })
        row.addView(button("移除目前 KTV 軌") {
            val i = activeSpinner.selectedItemPosition
            if (i in tracks.indices) {
                tracks.removeAt(i)
                refreshSpinners((i - 1).coerceAtLeast(0), -1)
                toast("已移除文字軌")
            }
        })
        box.addView(horizontalScrollable(row))

        val list = ListView(this)
        val labels = if (tracks.isEmpty()) listOf("目前沒有文字軌") else tracks.mapIndexed { i, t ->
            "${i + 1}. ${t.name}｜${if (t.timed) "有時間軸" else "無時間軸"}｜${if (t.estimated) "估算" else "原始時間碼"}"
        }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        list.setOnItemClickListener { _, _, pos, _ ->
            if (pos in tracks.indices) activeSpinner.setSelection(pos)
        }
        box.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)))
        box.addView(TextView(this).apply {
            text = "提示：主畫面只保留 KTV 與時間軸；文字軌詳細管理需要時才開啟。"
            textSize = 13f
        })

        AlertDialog.Builder(this)
            .setTitle("文字軌管理")
            .setView(box)
            .setPositiveButton("關閉", null)
            .show()
    }

    private fun activeTrack(): TextTrack? = tracks.getOrNull(activeSpinner.selectedItemPosition)

    private fun compareTrack(): TextTrack? {
        val i = compareSpinner.selectedItemPosition - 1
        return tracks.getOrNull(i)
    }

    private fun refreshSpinners(activeWanted: Int = activeSpinner.selectedItemPosition, compareWanted: Int = compareSpinner.selectedItemPosition - 1) {
        val names = tracks.map { it.name + if (it.estimated) "（估算）" else "" }
        activeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        compareSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("不比較") + names)
        if (tracks.isNotEmpty()) activeSpinner.setSelection(activeWanted.coerceIn(0, tracks.lastIndex))
        compareSpinner.setSelection(if (compareWanted in tracks.indices) compareWanted + 1 else 0)
        rebuild()
    }

    private fun autoAlign() {
        val duration = (player?.duration ?: 0) / 1000.0
        if (duration <= 0) return
        val base = tracks.firstOrNull { it.timed && it.segments.isNotEmpty() }
        tracks.forEach { track ->
            if (!track.timed && track.segments.isNotEmpty()) {
                val texts = track.segments.map { it.text }
                if (base != null) {
                    val joined = texts.joinToString(" ")
                    val n = base.segments.size
                    val step = (joined.length.toDouble() / n).coerceAtLeast(1.0)
                    val newSegments = mutableListOf<Segment>()
                    for (i in 0 until n) {
                        val a = (i * step).toInt().coerceAtMost(joined.length)
                        val b = if (i == n - 1) joined.length else ((i + 1) * step).toInt().coerceAtMost(joined.length)
                        newSegments += Segment(base.segments[i].start, base.segments[i].end, joined.substring(a, b).trim(), "", true)
                    }
                    track.segments = newSegments
                    track.timed = true
                    track.estimated = true
                } else {
                    val weights = texts.map { it.length.coerceAtLeast(1) }
                    val total = weights.sum().toDouble()
                    var current = 0.0
                    track.segments = texts.mapIndexed { i, text ->
                        val span = duration * weights[i] / total
                        val seg = Segment(current, (current + span).coerceAtMost(duration), text, "", true)
                        current += span
                        seg
                    }.toMutableList()
                    if (track.segments.isNotEmpty()) track.segments.last().end = duration
                    track.timed = true
                    track.estimated = true
                }
            }
        }
        rebuild()
    }

    // ---------- KTV / timeline ----------
    private fun rebuild() {
        val track = activeTrack() ?: run {
            fullText.text = ""
            timeline.adapter = null
            summary.text = "尚無文字軌"
            return
        }
        val compare = compareTrack()
        review = if (compare != null) Review.compare(track, compare) else emptyList()
        val cnt = review.groupingBy { it.level }.eachCount()
        summary.text = if (compare == null) "未比較" else "綠${cnt["green"] ?: 0} 黃${cnt["yellow"] ?: 0} 紅${cnt["red"] ?: 0}"

        ranges.clear()
        val builder = StringBuilder()
        track.segments.forEach { seg ->
            val start = builder.length
            builder.append(seg.text.trim()).append(" ")
            ranges += start to builder.length
        }
        plainFullText = builder.toString()
        fullText.text = plainFullText
        timeline.adapter = TimelineAdapter(this, track, review)
        currentIndex = -1
        updateViews()
    }

    private fun updateViews() {
        val p = player ?: return
        val d = p.duration.coerceAtLeast(0)
        val pos = p.currentPosition.coerceAtLeast(0)
        if (d > 0) seek.progress = (pos * 1000.0 / d).toInt()
        timeLabel.text = "${TrackParser.fmt(pos / 1000.0)} / ${TrackParser.fmt(d / 1000.0)}"
        val track = activeTrack() ?: return
        val now = pos / 1000.0
        val idx = track.segments.indexOfLast { it.start <= now }
        if (idx < 0) return
        val seg = track.segments[idx]
        if (seg.end > seg.start && now > seg.end && idx < track.segments.lastIndex) return

        val sb = SpannableStringBuilder(plainFullText)
        if (idx < ranges.size) {
            val (a, b) = ranges[idx]
            sb.setSpan(BackgroundColorSpan(Color.rgb(110, 95, 0)), a, b, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            val fraction = ((now - seg.start) / (seg.end - seg.start).coerceAtLeast(.08)).coerceIn(0.0, 1.0)
            val mid = (a + (b - a) * fraction).toInt().coerceIn(a, b)
            if (mid > a) {
                sb.setSpan(BackgroundColorSpan(Color.rgb(255, 213, 79)), a, mid, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(Color.BLACK), a, mid, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        fullText.text = sb

        if (idx != currentIndex) {
            currentIndex = idx
            (timeline.adapter as? TimelineAdapter)?.current = idx
            (timeline.adapter as? TimelineAdapter)?.notifyDataSetChanged()
            timeline.setSelection(idx)
            fullText.layout?.let { layout ->
                if (idx < ranges.size) {
                    val line = layout.getLineForOffset(ranges[idx].first)
                    fullScroll.smoothScrollTo(0, (layout.getLineTop(line) - fullScroll.height / 3).coerceAtLeast(0))
                }
            }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateViews()
            handler.postDelayed(this, 220)
        }
    }

    // ---------- AI tool dialog ----------
    private fun showAiTools() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        box.addView(TextView(this).apply {
            text = "✨ Gemini 智慧工具"
            textSize = 20f
            setTextColor(Color.rgb(25, 80, 150))
        })
        box.addView(TextView(this).apply {
            text = "順稿只送文字；只有『聽錄音核對／多人講者』會上傳音訊。503／429 會自動重試與切換備援模型。"
            textSize = 13f
            setPadding(0, dp(4), 0, dp(8))
        })

        val key = EditText(this).apply {
            hint = "Gemini API Key"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
            setText(prefs.getString("api_key", "") ?: "")
        }
        box.addView(key)

        val keyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val reveal = Button(this).apply {
            text = "👁 按住顯示"
            isAllCaps = false
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> key.transformationMethod = null
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> key.transformationMethod = PasswordTransformationMethod.getInstance()
                }
                key.setSelection(key.text.length)
                false
            }
        }
        keyRow.addView(reveal)
        keyRow.addView(button("儲存 Key") {
            prefs.edit().putString("api_key", key.text.toString().trim()).apply()
            toast("API Key 已儲存在本機 App 設定")
        })
        box.addView(keyRow)

        val model = Spinner(this)
        val models = listOf("gemini-3.8-flash", "gemini-3.7-flash", "gemini-3.6-flash")
        model.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, models)
        val savedModel = prefs.getString("model", models.first()) ?: models.first()
        model.setSelection(models.indexOf(savedModel).coerceAtLeast(0))
        box.addView(model)

        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        actionRow.addView(button("✨ 順稿（保留時間軸）") {
            val selected = model.selectedItem.toString()
            val k = key.text.toString().trim()
            prefs.edit().putString("model", selected).apply()
            dialog.dismiss()
            geminiPolish(k, selected)
        })
        actionRow.addView(button("🎧 聽錄音核對／多人講者") {
            val selected = model.selectedItem.toString()
            val k = key.text.toString().trim()
            prefs.edit().putString("model", selected).apply()
            dialog.dismiss()
            confirmAudioGemini(k, selected)
        })
        actionRow.addView(button("🔎 重新比對目前兩軌") {
            rebuild()
            dialog.dismiss()
        })
        box.addView(actionRow)
        box.addView(button("關閉") { dialog.dismiss() })

        dialog.setContentView(box)
        dialog.window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
        }
        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun setAiStatus(message: String, color: Int = Color.GRAY) {
        aiStatus.text = message
        aiStatus.setTextColor(color)
    }

    private fun geminiPolish(key: String, model: String) {
        val track = activeTrack() ?: run {
            toast("請先加入文字軌")
            return
        }
        if (key.isBlank()) {
            toast("請先輸入 API Key")
            return
        }
        if (aiBusy) {
            toast("目前已有 AI 工作進行中")
            return
        }
        aiBusy = true
        val activeBefore = activeSpinner.selectedItemPosition
        setAiStatus("AI：順稿中…", Color.rgb(220, 150, 0))
        Thread {
            try {
                val newTrack = GeminiApi.polish(track, key, model) { msg ->
                    runOnUiThread { setAiStatus("AI：$msg", Color.rgb(220, 150, 0)) }
                }
                runOnUiThread {
                    tracks += newTrack
                    refreshSpinners(activeBefore, tracks.lastIndex)
                    setAiStatus("AI：順稿完成", Color.rgb(40, 150, 70))
                    toast("順稿已新增為比較軌，原始 KTV 軌未被覆蓋")
                    aiBusy = false
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setAiStatus("AI：暫時失敗", Color.rgb(210, 60, 60))
                    toast(e.message ?: "Gemini 暫時無法使用；KTV 不受影響")
                    aiBusy = false
                }
            }
        }.start()
    }

    private fun confirmAudioGemini(key: String, model: String) {
        val uri = audioUri ?: run {
            toast("請先選擇錄音")
            return
        }
        if (key.isBlank()) {
            toast("請先輸入 API Key")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("確認上傳音訊")
            .setMessage("只有這項功能會將目前錄音檔上傳 Google Gemini，用於多人講者、時間軸與核對。其他播放與比對功能不會上傳音訊。要繼續嗎？")
            .setNegativeButton("取消", null)
            .setPositiveButton("繼續") { _, _ -> geminiAudio(uri, key, model) }
            .show()
    }

    private fun geminiAudio(uri: Uri, key: String, model: String) {
        if (aiBusy) {
            toast("目前已有 AI 工作進行中")
            return
        }
        aiBusy = true
        val activeBefore = activeSpinner.selectedItemPosition
        setAiStatus("AI：上傳並核對錄音中…", Color.rgb(220, 150, 0))
        Thread {
            try {
                val newTrack = GeminiApi.transcribeForReview(contentResolver, uri, key, model) { msg ->
                    runOnUiThread { setAiStatus("AI：$msg", Color.rgb(220, 150, 0)) }
                }
                runOnUiThread {
                    tracks += newTrack
                    refreshSpinners(activeBefore, tracks.lastIndex)
                    setAiStatus("AI：核對軌完成", Color.rgb(40, 150, 70))
                    toast("Gemini 核對軌已新增；原始 KTV 軌未改動")
                    aiBusy = false
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setAiStatus("AI：暫時失敗", Color.rgb(210, 60, 60))
                    toast(e.message ?: "Gemini 暫時無法使用；KTV 不受影響")
                    aiBusy = false
                }
            }
        }.start()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        super.onDestroy()
    }
}

class TimelineAdapter(
    private val ctx: android.content.Context,
    private val track: TextTrack,
    private val rev: List<ReviewResult>
) : BaseAdapter() {
    var current = -1
    override fun getCount() = track.segments.size
    override fun getItem(position: Int) = track.segments[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val box = (convertView as? LinearLayout) ?: LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 10, 12, 10)
        }
        box.removeAllViews()
        val seg = track.segments[position]
        val result = rev.getOrNull(position)
        val head = TextView(ctx).apply {
            text = "${TrackParser.fmt(seg.start)}–${TrackParser.fmt(seg.end)}  ${seg.speaker}"
            setTextColor(Color.rgb(30, 100, 170))
            textSize = 13f
        }
        val text = TextView(ctx).apply {
            this.text = seg.text
            textSize = 17f
            setTextColor(Color.DKGRAY)
        }
        box.addView(head)
        box.addView(text)
        if (result != null) {
            box.addView(TextView(ctx).apply {
                this.text = "比較：${result.other}"
                textSize = 14f
                setTextColor(Color.GRAY)
            })
            box.addView(TextView(ctx).apply {
                this.text = "${(result.score * 100).toInt()}%｜${result.note}"
                textSize = 13f
            })
        }
        box.setBackgroundColor(
            if (position == current) Color.rgb(220, 235, 250)
            else when (result?.level) {
                "green" -> Color.rgb(230, 248, 234)
                "yellow" -> Color.rgb(255, 249, 220)
                "red" -> Color.rgb(255, 230, 230)
                else -> Color.WHITE
            }
        )
        return box
    }
}
