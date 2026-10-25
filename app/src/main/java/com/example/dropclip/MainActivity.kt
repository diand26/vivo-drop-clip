package com.example.dropclip

import android.app.PictureInPictureParams
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.DragAndDropPermissions
import android.view.DragEvent
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.materialswitch.MaterialSwitch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 拖放剪贴板
 *
 * 任何应用把文字 / 图片 / 文件拖到本页的 dropZone，松手即写入系统剪贴板。
 * 走的是 Android 原生拖放框架（DragEvent + ClipData），vivo / 华为 / 小米
 * 的“超级拖放”都建立在同一套框架之上，因此不需要任何厂商 SDK。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var dropZone: View
    private lateinit var dropHint: TextView
    private lateinit var preview: ImageView
    private lateinit var statusView: TextView
    private lateinit var titleText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var btnReadClip: Button
    private lateinit var btnHistory: Button
    private lateinit var autoBackSwitch: MaterialSwitch
    private lateinit var historyStore: HistoryStore
    private lateinit var prefs: SharedPreferences

    /** 「小窗拖放」快捷方式要求启动后立刻进画中画，等 onResume 再执行。 */
    private var pendingPip = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    /** 剪贴板一变就记一条历史（本应用在前台时才收得到）。 */
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { captureClipboard() }

    /** 自动退到后台的延迟任务，onDestroy 时要撤掉。 */
    private val autoBackTask = Runnable { moveTaskToBack(true) }

    private val clipboard: ClipboardManager
        get() = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    /**
     * 必须用字段持有它：一旦被回收，对拖拽 uri 的临时读权限就失效，
     * copyToCache 会在读完之前抛 SecurityException。
     */
    private var dragPermissions: DragAndDropPermissions? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        dropZone = findViewById(R.id.dropZone)
        dropHint = findViewById(R.id.dropHint)
        preview = findViewById(R.id.preview)
        statusView = findViewById(R.id.status)
        titleText = findViewById(R.id.titleText)
        subtitleText = findViewById(R.id.subtitleText)
        btnReadClip = findViewById(R.id.btnReadClip)
        btnReadClip.setOnClickListener { readClipboard() }

        historyStore = HistoryStore(this)
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        btnHistory = findViewById(R.id.btnHistory)
        btnHistory.setOnClickListener { showHistory() }
        refreshHistoryButton()

        autoBackSwitch = findViewById(R.id.autoBackSwitch)
        autoBackSwitch.isChecked = prefs.getBoolean(KEY_AUTO_BACK, true)
        autoBackSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_AUTO_BACK, checked).apply()
        }

        // 监听剪贴板变化，攒出「粘贴历史」；Android 10+ 只有本应用在前台时才收得到
        runCatching { clipboard.addPrimaryClipChangedListener(clipListener) }

        dropZone.setOnDragListener { view, event ->
            when (event.action) {
                // 必须返回 true，否则后续 ENTERED / DROP 事件都收不到
                DragEvent.ACTION_DRAG_STARTED -> true

                DragEvent.ACTION_DRAG_ENTERED -> {
                    view.setBackgroundResource(R.drawable.bg_drop_zone_active)
                    dropHint.text = getString(R.string.drop_hint_release)
                    true
                }

                DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> {
                    resetDropZone()
                    true
                }

                DragEvent.ACTION_DROP -> {
                    dragPermissions?.release()
                    dragPermissions = requestDragPermissions(view, event)
                    resetDropZone()
                    handleDrop(event)
                    // 内容已经复制进自己的缓存，权限可以立刻还回去
                    dragPermissions?.release()
                    dragPermissions = null
                    true
                }

                else -> true
            }
        }

        handleLaunchIntent(intent)
    }

    // ---------------------------------------------------------------- 快捷方式入口

    /**
     * App Shortcuts / 控制中心磁贴带过来的 intent：
     *   dropclip://pip     → 启动后进画中画小窗
     *   dropclip://history → 直接打开粘贴历史
     * 其它情况按普通启动处理。数据在 intent.data 里，靠 scheme 区分。
     */
    private fun handleLaunchIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != SCHEME) return
        when (data.host) {
            HOST_PIP -> pendingPip = true
            HOST_HISTORY -> mainHandler.post { if (!isFinishing) showHistory() }
        }
    }

    /**
     * 单例 + singleTop，从快捷方式再次点进来走这里，而不是 onCreate。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
        if (pendingPip) maybeEnterSmallWindow()
    }

    override fun onResume() {
        super.onResume()
        if (pendingPip) maybeEnterSmallWindow()
    }

    /**
     * 试一把画中画（Android 7.0+，且设备/系统策略允许）。
     * 这是 App 自己能控制的、最接近“小窗”的形态：一个浮在别的应用上面的小窗口，
     * 拖放事件照样会送到窗口上。真正的小窗/分屏由系统决定，应用没有 API 强制。
     */
    private fun maybeEnterSmallWindow() {
        pendingPip = false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            setStatus(getString(R.string.status_pip_failed))
            return
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            setStatus(getString(R.string.status_pip_failed))
            return
        }
        if (isFinishing || isDestroyed || isInPictureInPictureMode) return

        // 窗口已经 attach 之后才能进，稍微等一帧
        mainHandler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            val ok = runCatching {
                enterPictureInPictureMode(
                    PictureInPictureParams.Builder()
                        .setAspectRatio(Rational(3, 4))
                        .build()
                )
            }.getOrDefault(false)
            setStatus(getString(if (ok) R.string.status_pip_on else R.string.status_pip_failed))
        }, 350L)
    }

    /** 小窗里空间很小，只留拖放区域，其它文字/按钮先收起来。 */
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        applyPipChrome(isInPictureInPictureMode)
    }

    /** Android 7.0 / 7.1 只有这个单参数回调。 */
    @Deprecated("Android 8.0 起用双参数版本")
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        applyPipChrome(isInPictureInPictureMode)
    }

    private fun applyPipChrome(inPip: Boolean) {
        val chrome = if (inPip) View.GONE else View.VISIBLE
        titleText.visibility = chrome
        subtitleText.visibility = chrome
        statusView.visibility = chrome
        btnHistory.visibility = chrome
        btnReadClip.visibility = chrome
        autoBackSwitch.visibility = chrome
    }

    // ---------------------------------------------------------------- 拖放权限

    /**
     * View.requestDragAndDropPermissions(DragEvent) 在公开 SDK 的 android.jar 里没有
     * （它是 @SystemApi），直接调用会得到 Unresolved reference，因此只能反射。
     * 反射失败时返回 null：纯文本拖放照样能处理，读 content:// 图片/文件会抛
     * SecurityException，由 handleDrop 兜底成「读不到来源数据」。
     */
    private fun requestDragPermissions(view: View, event: DragEvent): DragAndDropPermissions? {
        return try {
            val method = View::class.java.getMethod(
                "requestDragAndDropPermissions",
                DragEvent::class.java
            )
            method.invoke(view, event) as? DragAndDropPermissions
        } catch (t: Throwable) {
            null
        }
    }

    // ---------------------------------------------------------------- 接收拖放

    private fun handleDrop(event: DragEvent) {
        val clip: ClipData? = event.clipData
        if (clip == null || clip.itemCount == 0) {
            setStatus(getString(R.string.status_empty))
            return
        }

        val texts = ArrayList<String>()
        var imageUri: Uri? = null
        var imageFile: File? = null
        var fileUri: Uri? = null
        var fileName: String? = null
        // 有 uri 但读不出来（来源没给权限 / 反射被拦）的数量
        var unreadable = 0

        for (i in 0 until clip.itemCount) {
            val item = clip.getItemAt(i) ?: continue
            val uri = item.uri

            if (uri != null) {
                val mime = contentResolver.getType(uri)
                if (mime != null && mime.startsWith("image/")) {
                    val copied = copyToCache(uri, "img", mime)
                    if (copied == null) {
                        unreadable++
                    } else if (imageUri == null) {
                        imageUri = copied.uri
                        imageFile = copied.file
                    }
                    continue
                }
                if (mime != null && !mime.startsWith("text/")) {
                    val copied = copyToCache(uri, "file", mime)
                    if (copied == null) {
                        unreadable++
                    } else if (fileUri == null) {
                        fileUri = copied.uri
                        fileName = uri.lastPathSegment
                    }
                    continue
                }
            }

            // text/plain、text/html 都走这里；uri 为空但带文字的也算
            val text = item.coerceToText(this)?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) texts.add(text)
        }

        val image = imageUri
        val file = fileUri

        when {
            image != null -> {
                clipboard.setPrimaryClip(
                    ClipData.newUri(contentResolver, getString(R.string.clip_label_image), image)
                )
                showPreview(image)
                setStatus(getString(R.string.status_copied_image))
                toast("图片已复制")
                imageFile?.let {
                    addHistory(
                        ClipEntry(ClipEntry.Kind.IMAGE, "", it.absolutePath, System.currentTimeMillis())
                    )
                }
                scheduleAutoBack()
            }

            file != null -> {
                clipboard.setPrimaryClip(
                    ClipData.newUri(contentResolver, getString(R.string.clip_label_file), file)
                )
                hidePreview()
                setStatus(getString(R.string.status_copied_file, fileName ?: "文件"))
                toast("文件已复制")
                scheduleAutoBack()
            }

            texts.isNotEmpty() -> {
                val joined = texts.joinToString("\n")
                clipboard.setPrimaryClip(
                    ClipData.newPlainText(getString(R.string.clip_label_text), joined)
                )
                hidePreview()
                setStatus(getString(R.string.status_copied_text, joined.length))
                toast("文字已复制")
                addHistory(
                    ClipEntry(ClipEntry.Kind.TEXT, joined, null, System.currentTimeMillis())
                )
                scheduleAutoBack()
            }

            else -> {
                hidePreview()
                val msg = if (unreadable > 0) R.string.status_denied else R.string.status_unsupported
                setStatus(getString(msg))
            }
        }
    }

    // ---------------------------------------------------------------- 剪贴板

    private fun readClipboard() {
        val clip = clipboard.primaryClip
        if (clip == null || clip.itemCount == 0) {
            setStatus(getString(R.string.status_clip_empty))
            return
        }

        val item = clip.getItemAt(0) ?: return
        val uri = item.uri
        if (uri != null) {
            val mime = contentResolver.getType(uri)
            if (mime != null && mime.startsWith("image/")) {
                showPreview(uri)
                setStatus(getString(R.string.status_clip_image))
                return
            }
        }

        val text = item.coerceToText(this)?.toString().orEmpty()
        if (text.isEmpty()) {
            setStatus(getString(R.string.status_clip_empty))
        } else {
            setStatus(getString(R.string.status_clip_text, text))
        }
    }

    // ---------------------------------------------------------------- 粘贴历史

    private fun addHistory(entry: ClipEntry) {
        historyStore.add(entry)
        refreshHistoryButton()
    }

    private fun refreshHistoryButton() {
        val count = historyStore.size
        btnHistory.text = if (count > 0) {
            getString(R.string.btn_history_count, count)
        } else {
            getString(R.string.btn_history)
        }
    }

    /**
     * 剪贴板变化回调。只记文字：外部应用放进去的图片我们既读不到字节，
     * 也不该偷偷存一份。自己拖进来的图片在 handleDrop 里单独记（有缓存文件）。
     */
    private fun captureClipboard() {
        runCatching {
            val clip = clipboard.primaryClip ?: return
            val item = clip.getItemAt(0) ?: return

            val uri = item.uri
            val mime = uri?.let { contentResolver.getType(it) }
            if (uri != null && mime != null && mime.startsWith("image/")) return

            val text = item.coerceToText(this)?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return

            addHistory(ClipEntry(ClipEntry.Kind.TEXT, text, null, System.currentTimeMillis()))
        }
    }

    private fun showHistory() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_history, null)
        val list = view.findViewById<LinearLayout>(R.id.historyList)
        val emptyView = view.findViewById<TextView>(R.id.historyEmpty)
        val entries = historyStore.all()

        view.findViewById<TextView>(R.id.historyTitle).text =
            getString(R.string.btn_history) + "（" + entries.size + "）"
        emptyView.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE

        entries.forEach { entry ->
            val row = layoutInflater.inflate(R.layout.item_history, list, false)
            val thumb = row.findViewById<ImageView>(R.id.itemThumb)
            val textView = row.findViewById<TextView>(R.id.itemText)
            val meta = row.findViewById<TextView>(R.id.itemMeta)
            val stamp = timeFormat.format(Date(entry.time))

            if (entry.kind == ClipEntry.Kind.IMAGE) {
                textView.text = getString(R.string.clip_label_image)
                meta.text = getString(R.string.history_item_image, stamp)
                val file = entry.filePath?.let { File(it) }
                if (file != null && file.exists()) {
                    val bitmap = loadThumb(file, 144)
                    if (bitmap != null) {
                        thumb.visibility = View.VISIBLE
                        thumb.setImageBitmap(bitmap)
                    }
                }
            } else {
                textView.text = entry.text
                meta.text = getString(R.string.history_item_meta, stamp, entry.text.length)
            }

            row.setOnClickListener {
                restore(entry)
                dialog.dismiss()
            }
            list.addView(row)
        }

        view.findViewById<Button>(R.id.btnClearHistory).setOnClickListener {
            historyStore.clear()
            refreshHistoryButton()
            setStatus(getString(R.string.status_history_cleared))
            dialog.dismiss()
        }

        dialog.setContentView(view)
        dialog.show()
    }

    /** 点历史条目 → 重新放回剪贴板。 */
    private fun restore(entry: ClipEntry) {
        when (entry.kind) {
            ClipEntry.Kind.TEXT -> {
                clipboard.setPrimaryClip(
                    ClipData.newPlainText(getString(R.string.clip_label_text), entry.text)
                )
                hidePreview()
                setStatus(getString(R.string.status_restored_text))
                toast("已复制回剪贴板")
            }

            ClipEntry.Kind.IMAGE -> {
                val file = entry.filePath?.let { File(it) }
                if (file == null || !file.exists()) {
                    setStatus(getString(R.string.status_image_gone))
                    return
                }
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                clipboard.setPrimaryClip(
                    ClipData.newUri(contentResolver, getString(R.string.clip_label_image), uri)
                )
                showPreview(uri)
                setStatus(getString(R.string.status_restored_image))
                toast("图片已复制回剪贴板")
            }
        }
    }

    /**
     * 复制完成后自动退到后台，让源应用重新占满屏幕，接着就能直接长按粘贴。
     * 分屏 / 小窗下不做：那时源应用本来就在旁边，把这边收掉反而丢了落点。
     */
    private fun scheduleAutoBack() {
        if (!autoBackSwitch.isChecked) return
        if (isInMultiWindowMode) return
        // 画中画小窗本身就是“让位”的形态，再退到后台就把落点弄没了
        if (isInPictureInPictureModeCompat()) return
        mainHandler.removeCallbacks(autoBackTask)
        mainHandler.postDelayed(autoBackTask, 900L)
        setStatus(getString(R.string.status_autoback))
    }

    private fun isInPictureInPictureModeCompat(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(autoBackTask)
        runCatching { clipboard.removePrimaryClipChangedListener(clipListener) }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 缓存副本

    /** 落地到缓存的结果：给剪贴板用的 FileProvider uri，以及本地文件（历史要用绝对路径）。 */
    private class Cached(val uri: Uri, val file: File)

    /**
     * 把拖进来的 uri 复制到自己的 cache/clip/ 下，再返回 FileProvider 的 uri。
     * 必须先落地：原 uri 的读取权限只在本次拖放期间有效，直接放进剪贴板，
     * 之后别的应用粘贴时就拿不到数据了。
     */
    private fun copyToCache(source: Uri, prefix: String, mime: String?): Cached? {
        return try {
            val dir = File(cacheDir, "clip")
            if (!dir.exists()) dir.mkdirs()
            pruneCache(dir)

            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime.orEmpty()) ?: "bin"
            val target = File(dir, "${prefix}_${System.currentTimeMillis()}.$ext")

            val input = contentResolver.openInputStream(source) ?: return null
            input.use { ins ->
                target.outputStream().use { out -> ins.copyTo(out) }
            }
            Cached(FileProvider.getUriForFile(this, "$packageName.fileprovider", target), target)
        } catch (t: Throwable) {
            null
        }
    }

    /** 缓存目录只留最近 30 个文件，避免长期使用把 cache 撑满。 */
    private fun pruneCache(dir: File) {
        val files = dir.listFiles() ?: return
        if (files.size < 30) return
        files.sortedBy { it.lastModified() }
            .take(files.size - 29)
            .forEach { runCatching { it.delete() } }
    }

    // ---------------------------------------------------------------- 界面

    private fun showPreview(uri: Uri) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }

            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            }
            val bitmap = contentResolver.openInputStream(uri)
                ?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return

            preview.setImageBitmap(bitmap)
            preview.visibility = View.VISIBLE
            dropHint.visibility = View.GONE
        }
    }

    private fun hidePreview() {
        preview.setImageDrawable(null)
        preview.visibility = View.GONE
        dropHint.visibility = View.VISIBLE
    }

    /** 按上限降采样解码，避免把大图整张读进内存。 */
    private fun loadThumb(file: File, max: Int): android.graphics.Bitmap? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, max)
            }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        }.getOrNull()
    }

    private fun sampleSize(width: Int, height: Int, max: Int = 1080): Int {
        var sample = 1
        var w = width
        var h = height
        while (w > max || h > max) {
            sample *= 2
            w /= 2
            h /= 2
        }
        return sample
    }

    private fun resetDropZone() {
        dropZone.setBackgroundResource(R.drawable.bg_drop_zone)
        dropHint.text = getString(R.string.drop_hint_idle)
    }

    private fun setStatus(text: String) {
        statusView.text = text
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val PREFS = "dropclip"
        private const val KEY_AUTO_BACK = "auto_back"

        /** 快捷方式用的私有 scheme，见 res/xml/shortcuts.xml */
        private const val SCHEME = "dropclip"
        private const val HOST_PIP = "pip"
        private const val HOST_HISTORY = "history"
    }
}
