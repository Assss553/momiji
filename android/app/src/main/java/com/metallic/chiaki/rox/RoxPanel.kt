package com.metallic.chiaki.rox

import android.animation.ValueAnimator
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.view.Gravity
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs

/**
 * ROX side panel for the stream screen: a handle on the right edge opens a panel with switches.
 * Translation: reads text from the stream picture (ML Kit, on the phone), translates English -> Arabic
 * (ML Kit, model downloaded once) and draws the Arabic over the original text.
 */
class RoxPanel(
	private val activity: AppCompatActivity,
	private val root: FrameLayout,
	private val streamViews: List<SurfaceView>)
{
	private val main = Handler(Looper.getMainLooper())
	private val density = activity.resources.displayMetrics.density
	private fun dp(v: Int) = (v * density).toInt()

	private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
	private val translator = Translation.getClient(
		TranslatorOptions.Builder()
			.setSourceLanguage(TranslateLanguage.ENGLISH)
			.setTargetLanguage(TranslateLanguage.ARABIC)
			.build())

	private var enabled = false
	private var modelReady = false
	private var busy = false
	private var lastSig: IntArray? = null
	private val cache = HashMap<String, String>()

	private lateinit var layer: TranslationLayer
	private lateinit var panel: LinearLayout
	private lateinit var handle: TextView
	private lateinit var status: TextView
	private var open = false

	init {
		buildUi()
		activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
			override fun onPause(owner: LifecycleOwner) { main.removeCallbacks(tick) }
			override fun onResume(owner: LifecycleOwner) { if(enabled && modelReady) main.post(tick) }
			override fun onDestroy(owner: LifecycleOwner) {
				enabled = false
				main.removeCallbacks(tick)
				recognizer.close()
				translator.close()
			}
		})
	}

	// ---------- UI ----------

	private fun buildUi() {
		layer = TranslationLayer()
		root.addView(layer, FrameLayout.LayoutParams(-1, -1))

		panel = LinearLayout(activity).apply {
			orientation = LinearLayout.VERTICAL
			layoutDirection = View.LAYOUT_DIRECTION_RTL
			setPadding(dp(16), dp(16), dp(16), dp(16))
			background = GradientDrawable().apply {
				setColor(0xEE101418.toInt())
				cornerRadii = floatArrayOf(dp(16).toFloat(), dp(16).toFloat(), 0f, 0f, 0f, 0f, dp(16).toFloat(), dp(16).toFloat())
			}
			isClickable = true // takes the touches so they don't reach the game controls underneath
		}
		panel.addView(label("ROX", 18f, true))
		panel.addView(switchRow("الترجمة") { on -> setTranslation(on) })
		status = label("", 12f, false).apply { setTextColor(0xFFB0BEC5.toInt()) }
		panel.addView(status)
		val blur = switchRow("تغبيش اللقطات") { }
		blur.isEnabled = false
		blur.alpha = 0.4f
		panel.addView(blur)
		panel.addView(label("التغبيش: قريباً", 12f, false).apply { setTextColor(0xFFB0BEC5.toInt()) })
		root.addView(panel, FrameLayout.LayoutParams(dp(250), -2, Gravity.RIGHT or Gravity.CENTER_VERTICAL))
		panel.translationX = dp(250).toFloat()

		handle = TextView(activity).apply {
			text = "◀"
			setTextColor(Color.WHITE)
			gravity = Gravity.CENTER
			alpha = 0.7f
			background = GradientDrawable().apply {
				setColor(0x99000000.toInt())
				cornerRadii = floatArrayOf(dp(12).toFloat(), dp(12).toFloat(), 0f, 0f, 0f, 0f, dp(12).toFloat(), dp(12).toFloat())
			}
			setOnClickListener { toggle() }
		}
		root.addView(handle, FrameLayout.LayoutParams(dp(34), dp(88), Gravity.RIGHT or Gravity.CENTER_VERTICAL))
	}

	private fun label(t: String, sp: Float, bold: Boolean) = TextView(activity).apply {
		text = t; textSize = sp; setTextColor(Color.WHITE)
		if(bold) setTypeface(typeface, Typeface.BOLD)
		setPadding(0, dp(6), 0, dp(6))
	}

	private fun switchRow(t: String, onChange: (Boolean) -> Unit) = SwitchCompat(activity).apply {
		text = t; textSize = 15f; setTextColor(Color.WHITE)
		setPadding(0, dp(6), 0, dp(6))
		setOnCheckedChangeListener { _, c -> onChange(c) }
	}

	private fun toggle() {
		open = !open
		val w = dp(250).toFloat()
		panel.animate().translationX(if(open) 0f else w).setDuration(180).start()
		handle.animate().translationX(if(open) -w else 0f).setDuration(180).start()
		handle.text = if(open) "▶" else "◀"
	}

	// ---------- translation ----------

	private fun setTranslation(on: Boolean) {
		enabled = on
		if(!on) {
			main.removeCallbacks(tick)
			layer.clear()
			status.text = ""
			return
		}
		if(modelReady) { status.text = "شغّالة"; main.post(tick); return }
		status.text = "ينزّل نموذج الترجمة (مرة وحدة، يحتاج إنترنت)…"
		translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
			.addOnSuccessListener {
				modelReady = true
				if(enabled) { status.text = "شغّالة"; main.post(tick) }
			}
			.addOnFailureListener { status.text = "تعذّر تنزيل النموذج: تأكد من الإنترنت وجرّب مرة ثانية" }
	}

	private val tick = object : Runnable {
		override fun run() {
			if(!enabled || !modelReady) return
			if(!busy) { busy = true; capture() }
			main.postDelayed(this, 700)
		}
	}

	private fun streamView() = streamViews.firstOrNull {
		it.visibility == View.VISIBLE && it.width > 0 && it.height > 0 && it.holder.surface.isValid
	}

	private fun capture() {
		val v = streamView() ?: run { busy = false; return }
		val w = 1280
		val h = (w * v.height.toFloat() / v.width).toInt().coerceAtLeast(1)
		val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
		try {
			PixelCopy.request(v, bmp, PixelCopy.OnPixelCopyFinishedListener { r ->
				if(r == PixelCopy.SUCCESS) process(bmp, v) else done(bmp)
			}, main)
		} catch(e: Exception) {
			status.text = "تعذّر التقاط الصورة"
			done(bmp)
		}
	}

	private fun done(bmp: Bitmap) { bmp.recycle(); busy = false }

	private fun signature(bmp: Bitmap): IntArray {
		val s = Bitmap.createScaledBitmap(bmp, 32, 18, true)
		val px = IntArray(32 * 18)
		s.getPixels(px, 0, 32, 0, 0, 32, 18)
		s.recycle()
		return IntArray(px.size) { val c = px[it]; ((c shr 16 and 255) * 3 + (c shr 8 and 255) * 6 + (c and 255)) / 10 }
	}

	private fun process(bmp: Bitmap, v: SurfaceView) {
		val sig = signature(bmp)
		val last = lastSig
		if(last != null) {
			var d = 0
			for(i in sig.indices) d += abs(sig[i] - last[i])
			if(d / sig.size < 4) { done(bmp); return } // picture didn't change, keep what is shown
		}
		lastSig = sig
		recognizer.process(InputImage.fromBitmap(bmp, 0))
			.addOnSuccessListener { text -> translateBlocks(text, bmp, v) }
			.addOnFailureListener { done(bmp) }
	}

	private fun worth(s: String): Boolean {
		val letters = s.count { it.isLetter() }
		return letters >= 3 && letters * 2 >= s.length
	}

	private fun translateBlocks(text: Text, bmp: Bitmap, v: SurfaceView) {
		val blocks = text.textBlocks
			.filter { it.boundingBox != null && worth(it.text) }
			.take(8)
		val scale = v.width.toFloat() / bmp.width
		val a = IntArray(2); val b = IntArray(2)
		v.getLocationOnScreen(a); layer.getLocationOnScreen(b)
		val offX = (a[0] - b[0]).toFloat(); val offY = (a[1] - b[1]).toFloat()
		done(bmp)
		if(blocks.isEmpty()) { layer.clear(); return }

		val results = arrayOfNulls<String>(blocks.size)
		var pending = blocks.size
		fun finish() {
			if(--pending > 0) return
			val items = ArrayList<Item>()
			for(i in blocks.indices) {
				val tr = results[i] ?: continue
				val r = blocks[i].boundingBox!!
				items.add(makeItem(tr, RectF(r.left * scale + offX, r.top * scale + offY, r.right * scale + offX, r.bottom * scale + offY)))
			}
			layer.setItems(items)
		}
		for(i in blocks.indices) {
			val raw = blocks[i].text.replace('\n', ' ').trim()
			val cached = cache[raw]
			if(cached != null) { results[i] = cached; finish(); continue }
			translator.translate(raw)
				.addOnSuccessListener { t -> if(t.isNotBlank()) { cache[raw] = t; results[i] = t }; finish() }
				.addOnFailureListener { finish() }
		}
	}

	// ---------- drawing ----------

	private class Item(val bg: RectF, val layout: StaticLayout, val x: Float, val y: Float)

	private fun makeItem(t: String, r: RectF): Item {
		val width = maxOf(r.width(), dp(140).toFloat()).toInt()
		val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
		var size = minOf(dp(30).toFloat(), r.height())
		var layout: StaticLayout
		while(true) {
			paint.textSize = size
			layout = StaticLayout.Builder.obtain(t, 0, t.length, paint, width)
				.setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
				.setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_RTL)
				.build()
			if(layout.height <= maxOf(r.height() * 1.4f, dp(24).toFloat()) || size <= dp(11)) break
			size -= dp(1)
		}
		val pad = dp(4).toFloat()
		val x = r.centerX() - width / 2f
		val bg = RectF(x - pad, r.top - pad, x + width + pad, r.top + layout.height + pad)
		return Item(bg, layout, x, r.top)
	}

	private inner class TranslationLayer : View(activity) {
		private var items: List<Item> = emptyList()
		private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD9000000.toInt() }
		fun setItems(l: List<Item>) { items = l; invalidate() }
		fun clear() { items = emptyList(); lastSig = null; invalidate() }
		override fun onDraw(c: Canvas) {
			for(it in items) {
				c.drawRoundRect(it.bg, dp(6).toFloat(), dp(6).toFloat(), bgPaint)
				c.save(); c.translate(it.x, it.y); it.layout.draw(c); c.restore()
			}
		}
	}
}
