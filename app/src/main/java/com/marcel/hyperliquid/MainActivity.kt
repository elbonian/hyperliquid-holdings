package com.marcel.hyperliquid

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Version 1.3: UI/UX polish only. Every behavior from v1.2 is unchanged:
 * the same screen, data, and actions, with a dark trading-terminal look,
 * card rows, percent pills with direction arrows, animated refreshes,
 * pull-to-refresh, an inline error banner with retry, a designed empty
 * state, and search-field polish (clear button, search action, keyboard
 * dismissal on pick).
 *
 * API behavior mirrors scripts/hyperliquid_weekend.py, except the
 * baseline timestamp (see mostRecentSessionClose20Utc):
 * - POSTs to https://api.hyperliquid.xyz/info (public, no auth).
 * - Reads the xyz builder dex universe for xyz:<ticker> and BTC on the main
 *   dex as the IBIT proxy.
 * - Baseline is the open of the first hourly candle at or after the most
 *   recent regular-session close: 20:00 UTC (1pm PT) on the most recent
 *   weekday (Mon-Fri) at or before now. Current price is the live mark price.
 * - Rows sorted best to worst by percent vs that session close.
 * - On any API failure, shows an error and NO stale numbers.
 * - Known limitation: market holidays are NOT accounted for; the rule is
 *   purely weekday-based.
 * Read-only market data: no trading, no account, no API key, no login.
 *
 * Tracked list:
 * - Persisted in SharedPreferences under key "tracked" as a set of market
 *   keys. A key is either a xyz ticker (e.g. "SMH") or "BTC" for the IBIT
 *   proxy. Only markets that exist on Hyperliquid can be added, because
 *   search results come straight from the xyz universe (plus BTC).
 * - Fresh installs start with the v1.0 list (SMH, TSLA, SPCX, XBI, DRAM,
 *   BTC). Upgrades in place never reseed: reseeding happens only when the
 *   prefs key is absent entirely, so a user who removed everything keeps
 *   an empty list.
 */
class MainActivity : Activity() {

    private data class Row(val key: String, val label: String, val price: Double, val pct: Double)

    private val prefsName = "hyperliquid_holdings"
    private val prefsKeyTracked = "tracked"
    private val defaultTracked = listOf("SMH", "TSLA", "SPCX", "XBI", "DRAM", "BTC")

    // Dark terminal palette.
    private val colText = Color.parseColor("#E8ECF1")
    private val colMuted = Color.parseColor("#8A94A6")
    private val colGreen = Color.parseColor("#3DDC84")
    private val colRed = Color.parseColor("#FF6B7A")

    private lateinit var pullScroll: PullScrollView
    private lateinit var pullIndicator: TextView
    private lateinit var titleText: TextView
    private lateinit var asOfText: TextView
    private lateinit var errorText: TextView
    private lateinit var emptyState: TextView
    private lateinit var rowsContainer: LinearLayout
    private lateinit var refreshButton: Button
    private lateinit var searchField: EditText
    private lateinit var clearSearchButton: TextView
    private lateinit var searchStatus: TextView
    private lateinit var searchResults: LinearLayout

    // Rows from the last successful fetch, kept so removing a holding is instant.
    private var lastRows: List<Row> = emptyList()

    // Searchable market keys, cached for the session: xyz tickers plus BTC.
    private var universe: List<String>? = null
    private var universeLoading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        pullScroll = findViewById(R.id.pullScroll)
        pullIndicator = findViewById(R.id.pullIndicator)
        asOfText = findViewById(R.id.asOfText)
        titleText = findViewById(R.id.titleText)
        errorText = findViewById(R.id.errorText)
        emptyState = findViewById(R.id.emptyState)
        rowsContainer = findViewById(R.id.rowsContainer)
        refreshButton = findViewById(R.id.refreshButton)
        searchField = findViewById(R.id.searchField)
        clearSearchButton = findViewById(R.id.clearSearchButton)
        searchStatus = findViewById(R.id.searchStatus)
        searchResults = findViewById(R.id.searchResults)
        refreshButton.setOnClickListener { fetch() }
        errorText.setOnClickListener { fetch() }
        clearSearchButton.setOnClickListener { searchField.setText("") }
        searchField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard()
                true
            } else {
                false
            }
        }
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString() ?: ""
                clearSearchButton.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
                onSearchTextChanged(text)
            }
        })
        pullScroll.onPullProgress = { pulling, beyond ->
            if (pulling) {
                pullIndicator.visibility = View.VISIBLE
                pullIndicator.text = if (beyond) "Release to refresh" else "Pull to refresh"
            } else {
                pullIndicator.visibility = View.GONE
            }
        }
        pullScroll.onPullRefresh = {
            // Same action as the Refresh button, guarded the same way.
            if (refreshButton.isEnabled) fetch()
        }
        fetch()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchField.windowToken, 0)
    }

    // --- Tracked list persistence ---

    private fun trackedKeys(): MutableSet<String> {
        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        if (!prefs.contains(prefsKeyTracked)) {
            // Fresh install only: seed the v1.0 default list.
            prefs.edit().putStringSet(prefsKeyTracked, defaultTracked.toSet()).apply()
            return defaultTracked.toMutableSet()
        }
        return HashSet(prefs.getStringSet(prefsKeyTracked, emptySet()) ?: emptySet())
    }

    private fun saveTracked(keys: Set<String>) {
        getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit().putStringSet(prefsKeyTracked, keys).apply()
    }

    private fun labelFor(key: String): String {
        return if (key == "BTC") "BTC (IBIT proxy)" else key
    }

    // --- Main fetch and rendering ---

    private fun fetch() {
        refreshButton.isEnabled = false
        pullIndicator.visibility = View.GONE
        emptyState.visibility = View.GONE
        errorText.visibility = View.GONE
        rowsContainer.removeAllViews()
        asOfText.text = "Loading..."
        val baselineMs = mostRecentSessionClose20Utc(System.currentTimeMillis())
        val baselineDay = baselineDayName(baselineMs)
        Thread {
            try {
                val rows = loadRows(trackedKeys(), baselineMs)
                runOnUiThread {
                    refreshButton.isEnabled = true
                    titleText.text = "Holdings vs $baselineDay's close"
                    lastRows = rows
                    val stamp = SimpleDateFormat("EEE MMM d, h:mm a z", Locale.US).format(Date())
                    asOfText.text = "As of $stamp - Hyperliquid perps"
                    if (rows.isEmpty() && trackedKeys().isNotEmpty()) {
                        errorText.text = "No tracked holdings returned data.\nTap to retry"
                        errorText.visibility = View.VISIBLE
                    }
                    renderRows(rows, animate = true)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    refreshButton.isEnabled = true
                    // No stale numbers: leave the rows empty and show only the error.
                    lastRows = emptyList()
                    rowsContainer.removeAllViews()
                    emptyState.visibility = View.GONE
                    asOfText.text = "Hyperliquid perps"
                    titleText.text = "Holdings"
                    errorText.text =
                        "Could not load Hyperliquid data. Check your connection and try again.\nTap to retry"
                    errorText.visibility = View.VISIBLE
                }
            }
        }.start()
    }

    private fun renderRows(rows: List<Row>, animate: Boolean) {
        rowsContainer.removeAllViews()
        emptyState.visibility =
            if (rows.isEmpty() && trackedKeys().isEmpty()) View.VISIBLE else View.GONE
        if (rows.isEmpty()) return
        val maxAbs = (rows.maxOf { kotlin.math.abs(it.pct) }).coerceAtLeast(0.1)
        for (row in rows) {
            rowsContainer.addView(buildRowView(row, maxAbs, animate))
        }
        if (animate) {
            // Rows fade in, staggered, while their bars grow (see buildRowView).
            for (i in 0 until rowsContainer.childCount) {
                val child = rowsContainer.getChildAt(i)
                child.alpha = 0f
                ObjectAnimator.ofFloat(child, "alpha", 0f, 1f).apply {
                    duration = 200
                    startDelay = i * 40L
                    start()
                }
            }
        }
    }

    private fun barDrawable(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = 4f * resources.displayMetrics.density
        }
    }

    private fun selectableBackground(): Int {
        val outValue = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        return outValue.resourceId
    }

    private fun buildRowView(row: Row, maxAbs: Double, animate: Boolean): View {
        val up = row.pct >= 0
        val color = if (up) colGreen else colRed
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.card_bg)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            contentDescription =
                "${row.label} ${formatPrice(row.price, row.key == "BTC")} ${formatPct(row.pct)}"
        }
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val ticker = TextView(this).apply {
            text = row.label
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(colText)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val price = TextView(this).apply {
            text = formatPrice(row.price, row.key == "BTC")
            textSize = 16f
            typeface = Typeface.MONOSPACE
            setTextColor(colText)
            textAlignment = View.TEXT_ALIGNMENT_VIEW_END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
        }
        val remove = TextView(this).apply {
            text = "✕"
            textSize = 16f
            setTextColor(colMuted)
            gravity = Gravity.CENTER
            minWidth = dp(48)
            minHeight = dp(48)
            contentDescription = "Remove ${row.label}"
            setBackgroundResource(selectableBackground())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                val keys = trackedKeys()
                keys.remove(row.key)
                saveTracked(keys)
                errorText.visibility = View.GONE
                lastRows = lastRows.filter { it.key != row.key }
                renderRows(lastRows, animate = false)
            }
        }
        topRow.addView(ticker)
        topRow.addView(price)
        topRow.addView(remove)

        val frac = (kotlin.math.abs(row.pct) / maxAbs).toFloat()
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        val track = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = getDrawable(R.drawable.track_bg)
            layoutParams = LinearLayout.LayoutParams(0, dp(8), 1f)
        }
        val bar = View(this).apply {
            background = barDrawable(color)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, if (animate) 0f else frac
            )
        }
        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, if (animate) 1f else 1f - frac
            )
        }
        track.addView(bar)
        track.addView(spacer)
        val pill = TextView(this).apply {
            text = (if (up) "▲ " else "▼ ") + formatPct(row.pct)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(color)
            background = getDrawable(if (up) R.drawable.pill_green else R.drawable.pill_red)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        }
        bottomRow.addView(track)
        bottomRow.addView(pill)
        card.addView(topRow)
        card.addView(bottomRow)
        if (animate) {
            // Bars grow from zero to their proportional width.
            ValueAnimator.ofFloat(0f, frac).apply {
                duration = 350
                addUpdateListener {
                    val f = it.animatedValue as Float
                    (bar.layoutParams as LinearLayout.LayoutParams).weight = f
                    (spacer.layoutParams as LinearLayout.LayoutParams).weight = 1f - f
                    bar.requestLayout()
                    spacer.requestLayout()
                }
                start()
            }
        }
        return card
    }

    private fun formatPrice(price: Double, isBtc: Boolean): String {
        return if (isBtc) {
            "%,.0f".format(Locale.US, price)
        } else {
            "%.2f".format(Locale.US, price)
        }
    }

    private fun formatPct(pct: Double): String {
        val sign = if (pct >= 0) "+" else ""
        return "$sign${"%.2f".format(Locale.US, pct)}%"
    }

    // --- Search: only real Hyperliquid markets can be added ---

    private fun onSearchTextChanged(query: String) {
        val q = query.trim()
        if (q.isEmpty()) {
            searchStatus.visibility = View.GONE
            searchResults.removeAllViews()
            searchResults.visibility = View.GONE
            return
        }
        val cached = universe
        if (cached != null) {
            showSearchResults(q, cached)
            return
        }
        if (universeLoading) return
        universeLoading = true
        searchStatus.text = "Loading market list..."
        searchStatus.visibility = View.VISIBLE
        searchResults.visibility = View.GONE
        Thread {
            try {
                val xyz = post(JSONObject().put("type", "metaAndAssetCtxs").put("dex", "xyz"))
                val xyzUniverse = xyz.getJSONObject(0).getJSONArray("universe")
                val keys = mutableListOf<String>()
                for (i in 0 until xyzUniverse.length()) {
                    val name = xyzUniverse.getJSONObject(i).getString("name")
                    if (name.startsWith("xyz:")) keys.add(name.removePrefix("xyz:"))
                }
                keys.add("BTC")
                universe = keys.sorted()
                runOnUiThread {
                    universeLoading = false
                    showSearchResults(q, universe ?: emptyList())
                }
            } catch (e: Exception) {
                runOnUiThread {
                    universeLoading = false
                    searchStatus.text = "Could not load the market list. Check your connection and type again."
                    searchStatus.visibility = View.VISIBLE
                }
            }
        }.start()
    }

    private fun showSearchResults(query: String, keys: List<String>) {
        val q = query.lowercase(Locale.US)
        val tracked = trackedKeys()
        val matches = keys.filter { key ->
            key.lowercase(Locale.US).contains(q) || labelFor(key).lowercase(Locale.US).contains(q)
        }
        searchResults.removeAllViews()
        if (matches.isEmpty()) {
            searchStatus.text = "No Hyperliquid market matches \"$query\"."
            searchStatus.visibility = View.VISIBLE
            searchResults.visibility = View.GONE
            return
        }
        val addable = matches.filter { it !in tracked }
        if (addable.isEmpty()) {
            searchStatus.text = "Already tracking matching markets."
            searchStatus.visibility = View.VISIBLE
            searchResults.visibility = View.GONE
            return
        }
        searchStatus.visibility = View.GONE
        for (key in addable.take(50)) {
            val item = Button(this).apply {
                text = labelFor(key)
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                textSize = 14f
                setTextColor(colText)
                background = getDrawable(R.drawable.card_bg)
                minHeight = dp(48)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(4) }
                setOnClickListener {
                    val keysNow = trackedKeys()
                    keysNow.add(key)
                    saveTracked(keysNow)
                    hideKeyboard()
                    searchField.setText("")
                    fetch()
                }
            }
            searchResults.addView(item)
        }
        searchResults.visibility = View.VISIBLE
    }

    // --- Hyperliquid API, mirroring hyperliquid_weekend.py ---

    private fun loadRows(tracked: Set<String>, baselineMs: Long): List<Row> {
        val now = System.currentTimeMillis()

        val main = post(JSONObject().put("type", "metaAndAssetCtxs"))
        val mainUniverse = main.getJSONObject(0).getJSONArray("universe")
        val mainCtxs = main.getJSONArray(1)
        val mainIdx = indexByName(mainUniverse)

        val xyz = post(JSONObject().put("type", "metaAndAssetCtxs").put("dex", "xyz"))
        val xyzUniverse = xyz.getJSONObject(0).getJSONArray("universe")
        val xyzCtxs = xyz.getJSONArray(1)
        val xyzIdx = indexByName(xyzUniverse)

        val rows = mutableListOf<Row>()
        for (key in tracked) {
            val coin: String
            val mark: Double
            if (key == "BTC") {
                coin = "BTC"
                val idx = mainIdx[coin] ?: continue
                mark = mainCtxs.getJSONObject(idx).getString("markPx").toDouble()
            } else {
                coin = "xyz:$key"
                val idx = xyzIdx[coin] ?: continue
                mark = xyzCtxs.getJSONObject(idx).getString("markPx").toDouble()
            }
            val req = JSONObject()
                .put("coin", coin)
                .put("interval", "1h")
                .put("startTime", baselineMs)
                .put("endTime", now)
            val candles = post(JSONObject().put("type", "candleSnapshot").put("req", req))
            if (candles.length() == 0) continue
            val baseline = candles.getJSONObject(0).getString("o").toDouble()
            val pct = (mark / baseline - 1) * 100
            rows.add(Row(key, labelFor(key), mark, pct))
        }
        return rows.sortedByDescending { it.pct }
    }

    private fun indexByName(universe: JSONArray): Map<String, Int> {
        val map = HashMap<String, Int>()
        for (i in 0 until universe.length()) {
            map[universe.getJSONObject(i).getString("name")] = i
        }
        return map
    }

    /**
     * Most recent regular-session close: 20:00 UTC (1pm PT) on the most
     * recent weekday (Mon-Fri) at or before now. Weekends still yield
     * Friday, so weekend behavior is unchanged. Market holidays are NOT
     * accounted for: the rule is purely weekday-based.
     */
    private fun mostRecentSessionClose20Utc(nowMs: Long): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = nowMs
        // Python weekday(): Mon=0..Sun=6. Java DAY_OF_WEEK: Sun=1..Sat=7.
        val pythonWeekday = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
        // Step back to Friday when today is a weekend day.
        val daysBack = when (pythonWeekday) {
            5 -> 1 // Saturday -> Friday
            6 -> 2 // Sunday -> Friday
            else -> 0
        }
        cal.add(Calendar.DAY_OF_YEAR, -daysBack)
        cal.set(Calendar.HOUR_OF_DAY, 20)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        var candidate = cal.timeInMillis
        if (candidate >= nowMs) {
            // Today is a weekday but its 20:00 UTC close has not happened
            // yet: step back one weekday (Monday steps back to Friday).
            val closeWeekday = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
            cal.add(Calendar.DAY_OF_YEAR, if (closeWeekday == 0) -3 else -1)
            candidate = cal.timeInMillis
        }
        return candidate
    }

    /** Weekday name (e.g. "Tuesday") of a UTC timestamp, for the baseline label. */
    private fun baselineDayName(baselineMs: Long): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = baselineMs
        return SimpleDateFormat("EEEE", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(cal.time)
    }

    /** POSTs a JSON body to the Hyperliquid info endpoint and returns the JSON response. */
    private fun post(body: JSONObject): JSONArray {
        val conn = URL("https://api.hyperliquid.xyz/info").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 30_000
            conn.readTimeout = 30_000
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw RuntimeException("Hyperliquid HTTP ${conn.responseCode}")
            }
            val text = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
            // metaAndAssetCtxs returns a JSON array [meta, ctxs]; candleSnapshot returns an array.
            return JSONArray(text)
        } finally {
            conn.disconnect()
        }
    }
}
