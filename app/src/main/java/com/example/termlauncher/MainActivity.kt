package com.mewstercat.termlauncher

import android.app.Activity
import android.app.Dialog
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ClipData
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.provider.Settings
import android.view.DragEvent
import android.view.Window
import android.widget.Toast
import java.io.File
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.sin
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class App(val label: String, val pkg: String, val icon: Bitmap)

/** Falling white streaks: the rain/static wallpaper from the video. */
class RainView(c: Context) : View(c) {
    private val n = 90
    private val xs = FloatArray(n)
    private val ys = FloatArray(n)
    private val vs = FloatArray(n)
    private val ls = FloatArray(n)
    private val p = Paint().apply { color = Color.WHITE; strokeWidth = 2f }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        for (i in 0 until n) {
            xs[i] = Random.nextFloat() * w
            ys[i] = Random.nextFloat() * h
            vs[i] = 5f + Random.nextFloat() * 18f
            ls[i] = 20f + Random.nextFloat() * 90f
        }
    }

    override fun onDraw(c: Canvas) {
        for (i in 0 until n) {
            p.alpha = (30 + vs[i] * 6).toInt()
            c.drawLine(xs[i], ys[i], xs[i], ys[i] + ls[i], p)
            ys[i] += vs[i]
            if (ys[i] > height) { ys[i] = -ls[i]; xs[i] = Random.nextFloat() * width }
        }
        postInvalidateOnAnimation()
    }
}

private const val REQ_WALL = 1
private const val REQ_DEL = 2
private const val REQ_BIND = 3
private const val REQ_CFG = 4

/** Line-art analog clock that sits next to the digital time. */
class AnalogClock(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy) - 6f
        p.strokeWidth = 4f
        canvas.drawCircle(cx, cy, r, p)
        for (i in 0 until 12) {
            val a = Math.toRadians(i * 30.0)
            p.strokeWidth = 3f
            canvas.drawLine(cx + sin(a).toFloat() * r * .84f, cy - cos(a).toFloat() * r * .84f,
                cx + sin(a).toFloat() * r * .98f, cy - cos(a).toFloat() * r * .98f, p)
        }
        val t = Calendar.getInstance()
        val m = t.get(Calendar.MINUTE) + t.get(Calendar.SECOND) / 60f
        hand(canvas, cx, cy, (t.get(Calendar.HOUR) + m / 60f) * 30.0, r * .5f, 6f)
        hand(canvas, cx, cy, m * 6.0, r * .78f, 3f)
    }

    private fun hand(c: Canvas, cx: Float, cy: Float, deg: Double, len: Float, w: Float) {
        val a = Math.toRadians(deg)
        p.strokeWidth = w
        c.drawLine(cx, cy, cx + sin(a).toFloat() * len, cy - cos(a).toFloat() * len, p)
    }
}

class MainActivity : Activity() {
    private lateinit var apps: List<App>
    private lateinit var dock: LinearLayout
    private lateinit var clock: TextView
    private lateinit var status: TextView
    private lateinit var drawer: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var prompt: EditText
    private lateinit var rain: RainView
    private lateinit var wall: ImageView
    private lateinit var analog: AnalogClock
    private lateinit var widgets: LinearLayout
    private lateinit var host: AppWidgetHost
    private lateinit var awm: AppWidgetManager
    private var pendingId = -1
    private val h = Handler(Looper.getMainLooper())
    private var downY = 0f
    private val tick = object : Runnable {
        override fun run() { updateClock(); h.postDelayed(this, 1000) }
    }

    // ---- small helpers ----
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun box(stroke: Int = 2) = GradientDrawable().apply {
        setColor(Color.BLACK); setStroke(dp(stroke), Color.WHITE)
    }

    private fun tv(t: String, sp: Float = 12f, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(Color.WHITE)
        typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun bar(top: Int = 0) = View(this).apply {
        setBackgroundColor(Color.WHITE)
        layoutParams = LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(top) }
    }

    /** Redraws an app icon as high-contrast white on black. */
    private fun monoIcon(d: Drawable): Bitmap {
        val s = dp(40)
        val src = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, s, s); d.draw(Canvas(src))
        val k = 2.4f
        val g = floatArrayOf(.299f * k, .587f * k, .114f * k, 0f, 128f - 128f * k)
        val m = ColorMatrix(g + g + g + floatArrayOf(0f, 0f, 0f, 1f, 0f))
        val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(m) })
        return out
    }

    private fun loadApps(): List<App> {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(i, 0)
            .filter { it.activityInfo.packageName != packageName }
            .map { App(it.loadLabel(packageManager).toString(), it.activityInfo.packageName, monoIcon(it.loadIcon(packageManager))) }
            .sortedBy { it.label.lowercase() }
    }

    // ---- pins (dock) ----
    private fun prefs() = getSharedPreferences("term", MODE_PRIVATE)
    private fun pinsList(): MutableList<String> {
        val raw = prefs().getString("pinlist", null)
            ?: apps.take(5).joinToString("\n") { it.pkg }.also { prefs().edit().putString("pinlist", it).apply() }
        return raw.split("\n").filter { it.isNotBlank() }.toMutableList()
    }
    private fun savePins(l: List<String>) = prefs().edit().putString("pinlist", l.joinToString("\n")).apply()
    private fun pins(): Set<String> = pinsList().toSet()
    private fun togglePin(pkg: String) {
        val l = pinsList()
        if (!l.remove(pkg)) l.add(pkg)
        savePins(l); refreshDock(); fill(prompt.text.toString())
    }

    // ---- ui ----
    private fun cell(a: App, size: Int, showLabel: Boolean, pinned: Boolean): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(3), dp(6), dp(3), dp(6))
        addView(ImageView(context).apply {
            setImageBitmap(if (prefs().getBoolean("colors", false)) { try { colorIcon(a.pkg) } catch (e: Exception) { a.icon } } else a.icon)
            background = box(if (pinned) 4 else 2)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }, LinearLayout.LayoutParams(dp(size), dp(size)))
        if (showLabel) addView(tv(a.label.take(10).lowercase(), 9f).apply { gravity = Gravity.CENTER; maxLines = 1 })
        setOnClickListener { launch(a) }
        setOnLongClickListener {
            if (showLabel) appMenu(a)
            else startDragAndDrop(ClipData.newPlainText("pkg", a.pkg), View.DragShadowBuilder(this), null, 0)
            true
        }
    }

    private fun refreshDock() {
        dock.removeAllViews()
        pinsList().mapNotNull { k -> apps.find { it.pkg == k } }.take(6)
            .forEach { dock.addView(cell(it, 52, false, false)) }
    }

    private fun match(q: String) = apps.filter { it.label.contains(q.trim(), true) }

    private fun fill(q: String) {
        list.removeAllViews()
        val p = pins()
        match(q).chunked(4).forEach { row ->
            val r = LinearLayout(this)
            row.forEach { a -> r.addView(cell(a, 56, true, a.pkg in p), LinearLayout.LayoutParams(0, -2, 1f)) }
            repeat(4 - row.size) { r.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
            list.addView(r)
        }
    }

    private fun launch(a: App) {
        packageManager.getLaunchIntentForPackage(a.pkg)?.let { startActivity(it) }
        closeDrawer()
    }

    private fun openDrawer() {
        drawer.visibility = View.VISIBLE
        prompt.setText(""); fill(""); prompt.requestFocus()
    }

    private fun closeDrawer() {
        drawer.visibility = View.GONE
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(prompt.windowToken, 0)
    }

    private fun updateClock() {
        val d = Date()
        val bat = (getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        clock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(d)
        analog.invalidate()
        status.text = "term//home  " + SimpleDateFormat("EEE dd MMM", Locale.getDefault()).format(d).lowercase() + "  bat " + bat + "%"
    }

    // ---- option panels ----
    private fun panel(title: String, vararg opts: Pair<String, () -> Unit>) {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = box()
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addView(tv(title, 12f, true).apply { setPadding(0, 0, 0, dp(6)) })
            opts.forEach { (label, act) ->
                addView(tv("> " + label, 15f).apply {
                    setPadding(dp(4), dp(10), dp(4), dp(10))
                    setOnClickListener { d.dismiss(); act() }
                })
            }
        }
        d.setContentView(ScrollView(this).apply { addView(col) })
        d.show()
        d.window?.setLayout(dp(300), -2)
    }

    private fun appMenu(a: App) {
        val fav = pinsList().contains(a.pkg)
        panel(a.label.lowercase(),
            (if (fav) "unfavorite" else "favorite") to { togglePin(a.pkg) },
            "app info" to { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg))) },
            "debug" to { debugInfo(a) },
            "uninstall" to { startActivityForResult(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg)), REQ_DEL) })
    }

    private fun debugInfo(a: App) {
        try {
            val i = packageManager.getPackageInfo(a.pkg, 0)
            val f = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            val lines = listOf(
                "pkg " + a.pkg,
                "ver " + i.versionName + " (" + i.longVersionCode + ")",
                "target sdk " + i.applicationInfo?.targetSdkVersion,
                "installed " + f.format(Date(i.firstInstallTime)),
                "updated " + f.format(Date(i.lastUpdateTime)))
            panel("debug//" + a.label.lowercase(), *lines.map { it to {} }.toTypedArray())
        } catch (e: Exception) {
            Toast.makeText(this, "no info available", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- wallpaper (png / jpg / gif) ----
    private fun pickWall() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), REQ_WALL)
    }

    private fun loadWall() {
        (wall.drawable as? AnimatedImageDrawable)?.stop()
        val f = File(filesDir, "wall")
        if (!f.exists()) {
            wall.setImageDrawable(null); wall.visibility = View.GONE; rain.visibility = View.VISIBLE
            return
        }
        try {
            val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(f)) { dec, info, _ ->
                dec.setTargetSampleSize(maxOf(1, maxOf(info.size.width, info.size.height) / 1600))
            }
            wall.setImageDrawable(d)
            if (d is AnimatedImageDrawable) { d.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE; d.start() }
            wall.visibility = View.VISIBLE; rain.visibility = View.GONE
        } catch (e: Exception) {
            Toast.makeText(this, "can't read that image", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- widgets ----
    private fun wids(): List<Int> = (prefs().getString("wids", "") ?: "").split(",").mapNotNull { it.toIntOrNull() }
    private fun saveWids(l: List<Int>) = prefs().edit().putString("wids", l.joinToString(",")).apply()

    private fun pickWidget() {
        val ps = awm.installedProviders.sortedBy { it.loadLabel(packageManager).lowercase() }
        panel("add widget", *ps.map { p -> p.loadLabel(packageManager).lowercase() to { bindWidget(p) } }.toTypedArray())
    }

    private fun bindWidget(p: AppWidgetProviderInfo) {
        val id = host.allocateAppWidgetId()
        pendingId = id
        if (awm.bindAppWidgetIdIfAllowed(id, p.provider)) configureWidget(id)
        else startActivityForResult(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, p.provider), REQ_BIND)
    }

    private fun configureWidget(id: Int) {
        if (awm.getAppWidgetInfo(id)?.configure != null) host.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CFG, null)
        else finishWidget(id)
    }

    private fun finishWidget(id: Int) { saveWids(wids() + id); addWidget(id) }

    private fun addWidget(id: Int) {
        val info = awm.getAppWidgetInfo(id) ?: return
        val v = host.createView(this, id, info)
        v.setAppWidget(id, info)
        val fr = FrameLayout(this)
        fr.background = box(); fr.setPadding(dp(3), dp(3), dp(3), dp(3))
        fr.addView(v)
        fr.addView(tv("[x]", 10f).apply { setOnClickListener { removeWidget(id, fr) } },
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
        widgets.addView(fr, LinearLayout.LayoutParams(-1, dp(110)).apply { topMargin = dp(8) })
    }

    private fun removeWidget(id: Int, v: View) {
        host.deleteAppWidgetId(id); saveWids(wids() - id); widgets.removeView(v)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        when (req) {
            REQ_WALL -> if (res == RESULT_OK) data?.data?.let { u ->
                contentResolver.openInputStream(u)?.use { i -> File(filesDir, "wall").outputStream().use { o -> i.copyTo(o) } }
                loadWall()
            }
            REQ_DEL -> { apps = loadApps(); refreshDock(); fill(prompt.text.toString()) }
            REQ_BIND -> if (res == RESULT_OK) configureWidget(pendingId) else host.deleteAppWidgetId(pendingId)
            REQ_CFG -> if (res == RESULT_OK) finishWidget(pendingId) else host.deleteAppWidgetId(pendingId)
        }
    }

    // ---- settings panel (opened from [ cfg ]) ----
    private fun settings() {
        val dims = listOf("bright", "soft", "dark")
        val dim = prefs().getInt("dim", 2)
        val analogOn = prefs().getBoolean("analog", true)
        val colorsOn = prefs().getBoolean("colors", false)
        panel("settings",
            "wallpaper: pick image / gif" to { pickWall() },
            "wallpaper: reset to rain" to { File(filesDir, "wall").delete(); loadWall() },
            "wallpaper dim: " + dims[dim] to { prefs().edit().putInt("dim", (dim + 1) % 3).apply(); applyDim(); settings() },
            "icon colors: " + (if (colorsOn) "on" else "off") to { toggleColors(!colorsOn) },
            "add widget" to { pickWidget() },
            "move widgets" to { moveWidgets() },
            "analog clock: " + (if (analogOn) "on" else "off") to { toggleAnalog(!analogOn); settings() },
            "refresh app list" to { apps = loadApps(); refreshDock(); fill(prompt.text.toString()) },
            "default launcher" to { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) })
    }


    private fun applyDim() {
        val v = intArrayOf(0xFF, 0xB4, 0x8C)[prefs().getInt("dim", 2)]
        wall.colorFilter = PorterDuffColorFilter(Color.rgb(v, v, v), PorterDuff.Mode.MULTIPLY)
    }

    private fun toggleAnalog(on: Boolean) {
        prefs().edit().putBoolean("analog", on).apply()
        analog.visibility = if (on) View.VISIBLE else View.GONE
    }
// ---- icon colors (on/off) ----
private val colorCache = HashMap<String, Bitmap>()

private fun colorIcon(pkg: String): Bitmap = colorCache.getOrPut(pkg) {
    val s = dp(40)
    val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val d = packageManager.getApplicationIcon(pkg)
    d.setBounds(0, 0, s, s); d.draw(Canvas(b))
    b
}

private fun toggleColors(on: Boolean) {
    prefs().edit().putBoolean("colors", on).apply()
    refreshDock(); fill(prompt.text.toString())
    settings()
}

// ---- move widgets up / down ----
private fun rebuildWidgets() { widgets.removeAllViews(); wids().forEach { addWidget(it) } }

private fun moveWidgets() {
    val ids = wids()
    if (ids.isEmpty()) { Toast.makeText(this, "no widgets yet", Toast.LENGTH_SHORT).show(); return }
    val rows = ids.flatMapIndexed { i, id ->
        val label = (awm.getAppWidgetInfo(id)?.loadLabel(packageManager) ?: "widget").toString().lowercase()
        val name = (i + 1).toString() + ". " + label
        listOf<Pair<String, () -> Unit>>(
            "up: " + name to { moveWidget(i, -1) },
            "down: " + name to { moveWidget(i, 1) })
    }
    panel("move widgets", *rows.toTypedArray())
}

private fun moveWidget(i: Int, d: Int) {
    val l = wids().toMutableList()
    val j = i + d
    if (j in l.indices) { l.add(j, l.removeAt(i)); saveWids(l); rebuildWidgets() }
    moveWidgets()
}
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        apps = loadApps()

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        rain = RainView(this)
        root.addView(rain)
        wall = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE
            colorFilter = PorterDuffColorFilter(0xFF8C8C8C.toInt(), PorterDuff.Mode.MULTIPLY)
        }
        root.addView(wall)

        // home: top bar, status box, dock + clock panel, apps button, bottom bar
        val home = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        home.addView(bar())
        status = tv("term//home", 11f).apply { setPadding(dp(6), dp(6), dp(6), dp(6)); background = box(1) }
        val cfg = tv("[ cfg ]", 11f, true).apply {
            setPadding(dp(8), dp(6), dp(8), dp(6)); background = box(1); setOnClickListener { settings() }
        }
        home.addView(LinearLayout(this).apply {
            addView(status, LinearLayout.LayoutParams(0, -2, 1f))
            addView(cfg, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        })

        val mid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        dock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), dp(8), 0) }
        dock.setOnDragListener { _, e ->
            if (e.action == DragEvent.ACTION_DROP) {
                val pkg = e.clipData.getItemAt(0).text.toString()
                var idx = dock.childCount
                for (i in 0 until dock.childCount) {
                    val c = dock.getChildAt(i)
                    if (e.y < c.top + c.height / 2f) { idx = i; break }
                }
                val l = pinsList()
                val from = l.indexOf(pkg)
                if (from >= 0) { l.removeAt(from); if (from < idx) idx-- }
                l.add(idx.coerceIn(0, l.size), pkg)
                savePins(l); refreshDock()
            }
            true
        }
        mid.addView(dock, LinearLayout.LayoutParams(-2, -1))
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, 0) }
        clock = tv("", 30f, true).apply { setPadding(dp(8), dp(6), dp(8), dp(6)); gravity = Gravity.CENTER_VERTICAL; background = box() }
        analog = AnalogClock(this).apply { background = box() }
        info.addView(LinearLayout(this).apply {
            addView(clock, LinearLayout.LayoutParams(0, dp(76), 1f))
            addView(analog, LinearLayout.LayoutParams(dp(76), dp(76)).apply { leftMargin = dp(6) })
        })
        widgets = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(ScrollView(this).apply { addView(widgets) }, LinearLayout.LayoutParams(-1, 0, 1f))
        mid.addView(info, LinearLayout.LayoutParams(0, -1, 1f))
        home.addView(mid, LinearLayout.LayoutParams(-1, 0, 1f))

        val appsBtn = tv("> apps", 14f, true).apply {
            setPadding(dp(10), dp(12), dp(10), dp(12)); background = box()
            setOnClickListener { openDrawer() }
        }
        home.addView(appsBtn)
        home.addView(bar(8))
        root.addView(home)

        // drawer: terminal-style filter prompt + outlined icon grid
        drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            isClickable = true
            visibility = View.GONE
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        drawer.addView(bar())
        prompt = EditText(this).apply {
            hint = "> filter apps"
            setHintTextColor(0x88FFFFFF.toInt()); setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE; setSingleLine(); background = box()
            setPadding(dp(10), dp(10), dp(10), dp(10))
            imeOptions = EditorInfo.IME_ACTION_GO
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(e: Editable?) { fill(e?.toString().orEmpty()) }
                override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
                override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            })
            setOnEditorActionListener { _, _, _ -> match(text.toString()).firstOrNull()?.let { launch(it) }; true }
        }
        drawer.addView(prompt, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(8) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        drawer.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        drawer.addView(bar(8))
        root.addView(drawer)

        setContentView(root)
        host = AppWidgetHost(this, 42)
        awm = AppWidgetManager.getInstance(this)
        refreshDock()
        applyDim()
        analog.visibility = if (prefs().getBoolean("analog", true)) View.VISIBLE else View.GONE
        loadWall()
        wids().forEach { addWidget(it) }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) downY = ev.rawY
        if (ev.action == MotionEvent.ACTION_UP && drawer.visibility != View.VISIBLE && downY - ev.rawY > dp(120)) openDrawer()
        return super.dispatchTouchEvent(ev)
    }

    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); closeDrawer() }
    override fun onBackPressed() { if (drawer.visibility == View.VISIBLE) closeDrawer() }
    override fun onStart() { super.onStart(); host.startListening() }
    override fun onStop() { super.onStop(); host.stopListening() }
    override fun onResume() { super.onResume(); h.post(tick); (wall.drawable as? AnimatedImageDrawable)?.start() }
    override fun onPause() { super.onPause(); h.removeCallbacks(tick); (wall.drawable as? AnimatedImageDrawable)?.stop() }
}
