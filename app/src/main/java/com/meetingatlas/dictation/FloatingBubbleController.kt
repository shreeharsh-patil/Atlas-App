package com.meetingatlas.dictation

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.roundToInt

/** Owns the small system overlay window. No Activity, input method or continuous idle polling. */
internal class FloatingBubbleController(
    serviceContext: Context,
    private val tap: () -> Unit,
    private val hold: () -> Long?,
    private val release: (Long) -> Unit,
    private val cancelHold: (Long) -> Unit,
    private val cancel: () -> Unit,
    private val reposition: (Float, Float) -> Unit,
) {
    private val context: Context = serviceContext.applicationContext.let { app ->
        if (Build.VERSION.SDK_INT >= 30) {
            val display = requireNotNull(app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY))
            app.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else app
    }
    private val windows = context.getSystemService(WindowManager::class.java)
    private val root = FrameLayout(context).apply {
        clipChildren = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val middle = BubbleButton(context, Glyph.Wave)
    private val left = BubbleButton(context, Glyph.Cancel)
    private val right = BubbleButton(context, Glyph.Done)
    @SuppressLint("RtlHardcoded") // Keyboard and window coordinates are physical.
    private val params = WindowManager.LayoutParams(
        dp(56), dp(56), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        title = "Atlas dictation"
        if (Build.VERSION.SDK_INT >= 30) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
        } else if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }
    private var attached = false
    val visible get() = attached && root.isShown
    private var options = DictationSettings()
    private var cachedStatus = DictationStatus()
    private var field = Rect()
    private var keyboard: Rect? = null
    private var progress = 0f
    private var shrink = 0f
    private var expansion: ValueAnimator? = null
    private var shrinking: ValueAnimator? = null
    private var attachedAlpha: Float? = null
    private var lastPlacement: List<Int>? = null
    private val levels = FloatArray(7)
    private var pressed = false
    private var held = false
    private var moving = false
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var lockedCenter = 0
    private var holdId: Long? = null
    private var xOverride: Float? = null
    private var yOverride: Float? = null
    private var movingX = 0
    private var movingY = 0
    private var limits = Rect()
    private val longPress = Runnable {
        if (pressed && !moving && !cachedStatus.busy) {
            held = true
            holdId = hold()
        }
    }
    private val idleShrink = Runnable {
        if (attached && options.idleShrink && cachedStatus.phase == DictationPhase.IDLE && !pressed && !moving) {
            animateShrink(1f)
        }
    }

    init {
        for (button in listOf(left, middle, right)) root.addView(button, FrameLayout.LayoutParams(dp(48), dp(48)))
        left.contentDescription = "Cancel dictation"
        right.contentDescription = "Done dictating"
        left.setOnClickListener { cancel() }
        right.setOnClickListener { tap() }
        middle.setOnClickListener { tap() }
        hookGestures()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun hookGestures() {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        middle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressed = true; held = false; moving = false; holdId = null
                    downX = event.rawX; downY = event.rawY
                    startX = params.x; startY = params.y
                    lockedCenter = params.x + params.width / 2
                    view.isPressed = true
                    root.removeCallbacks(idleShrink)
                    if (!cachedStatus.busy) view.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!pressed) return@setOnTouchListener false
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!held && !moving && (abs(dx) > slop || abs(dy) > slop)) {
                        view.removeCallbacks(longPress)
                        moving = true
                    }
                    if (moving) {
                        movingX = startX + dx.roundToInt()
                        movingY = startY + dy.roundToInt()
                        layout()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!pressed) return@setOnTouchListener false
                    view.removeCallbacks(longPress)
                    view.isPressed = false
                    pressed = false
                    val id = holdId
                    val isUp = event.actionMasked == MotionEvent.ACTION_UP
                    val dragged = moving
                    holdId = null
                    moving = false
                    if (id != null) {
                        if (isUp) release(id) else cancelHold(id)
                    } else if (isUp && dragged) {
                        val x = if (params.x - limits.left < limits.right - params.x) 0f else 1f
                        val y = fraction(params.y, limits.top, limits.bottom)
                        xOverride = x; yOverride = y
                        reposition(x, y)
                    } else if (isUp && !held && !cachedStatus.busy) {
                        view.performClick()
                    }
                    layout()
                    scheduleShrink()
                    true
                }
                else -> pressed
            }
        }
    }

    fun show(settings: DictationSettings, status: DictationStatus, bounds: Rect, keyboard: Rect?): Boolean {
        val isNew = !attached
        val statusChanged = cachedStatus != status
        val shrinkOptionChanged = options.idleShrink != settings.idleShrink
        if (settings.positionX != options.positionX || settings.positionY != options.positionY) {
            xOverride = null; yOverride = null
        }
        options = settings
        cachedStatus = status
        field = bounds
        this.keyboard = keyboard
        if (isNew || statusChanged || shrinkOptionChanged) {
            val controls = status.busy
            val processing = status.phase in setOf(DictationPhase.PROCESSING, DictationPhase.INSERTING, DictationPhase.RECOVERING)
            if (!settings.idleShrink || status.phase != DictationPhase.IDLE) animateShrink(0f)
            animateExpansion(if (controls) 1f else 0f)
            middle.glyph = when {
                processing -> Glyph.Processing
                status.phase == DictationPhase.ERROR -> Glyph.Error
                status.phase == DictationPhase.SUCCESS -> Glyph.Done
                else -> Glyph.Wave
            }
            middle.contentDescription = when {
                status.phase == DictationPhase.LISTENING -> "Listening"
                status.phase == DictationPhase.STARTING -> "Opening microphone"
                processing -> status.message
                status.phase == DictationPhase.ERROR -> if (status.canRetry) "Retry dictation" else "Open Atlas. ${status.message}"
                status.phase == DictationPhase.SUCCESS && !status.message.startsWith("Inserted") -> "Open Atlas for text"
                else -> "Start dictation. Hold to talk; drag to move."
            }
            middle.isClickable = !controls
            middle.live = status.phase == DictationPhase.LISTENING
            if (!middle.live) middle.samples = null
            middle.invalidate()
            if (!isNew && statusChanged && status.phase in setOf(DictationPhase.ERROR, DictationPhase.SUCCESS, DictationPhase.CANCELLED)) {
                Toast.makeText(context, status.message, Toast.LENGTH_SHORT).show()
            }
        }
        if (!layout()) return false
        if (isNew || statusChanged || shrinkOptionChanged) scheduleShrink()
        return true
    }

    fun level(value: Float) {
        if (!middle.live) return
        levels.copyInto(levels, 0, 1, levels.size)
        levels[levels.lastIndex] = value.coerceIn(0f, 1f)
        middle.samples = levels
        middle.postInvalidateOnAnimation()
    }

    fun hide() {
        middle.removeCallbacks(longPress)
        root.removeCallbacks(idleShrink)
        expansion?.cancel()
        shrinking?.cancel()
        pressed = false; moving = false
        val activeHold = holdId
        holdId = null
        if (attached) {
            attached = false
            runCatching { windows.removeViewImmediate(root) }
        }
        progress = 0f; shrink = 0f
        levels.fill(0f)
        lastPlacement = null; attachedAlpha = null
        activeHold?.let(cancelHold)
    }

    private fun scheduleShrink() {
        root.removeCallbacks(idleShrink)
        if (options.idleShrink && cachedStatus.phase == DictationPhase.IDLE) root.postDelayed(idleShrink, 5000)
    }

    private fun animateExpansion(target: Float) {
        expansion?.cancel()
        if (!attached || progress == target || !motion()) progress = target
        else expansion = animate(progress, target, 165) { progress = it }
    }

    private fun animateShrink(target: Float) {
        shrinking?.cancel()
        if (!attached || shrink == target || !motion()) { shrink = target; if (attached) layout() }
        else shrinking = animate(shrink, target, 160) { shrink = it }
    }

    private fun animate(start: Float, end: Float, ms: Long, update: (Float) -> Unit) = ValueAnimator.ofFloat(start, end).apply {
        duration = ms
        interpolator = DecelerateInterpolator()
        addUpdateListener { update(it.animatedValue as Float); if (attached) layout() }
        start()
    }

    private fun layout(): Boolean {
        val box = screen()
        val idleDiameter = options.size
        val height = dp((idleDiameter + (48 - idleDiameter) * progress).roundToInt().coerceAtLeast(48))
        val visualIdle = idleDiameter.toFloat() - ((idleDiameter - 28) * shrink)
        val crossWidth = dp(50)
        val side = (crossWidth * progress).roundToInt()
        val center = dp((idleDiameter + (72 - idleDiameter) * progress).roundToInt())
        val width = center + side * 2
        val keyboardTop = keyboard?.top?.takeIf { it > box.top + height * 2 }
            ?: (field.top - dp(8)).takeIf { it > box.top + height * 2 }
            ?: box.bottom
        limits = Rect(box.left + dp(6), box.top + dp(6),
            (box.right - width - dp(8)).coerceAtLeast(box.left + dp(6)),
            (keyboardTop - height - dp(8)).coerceAtLeast(box.top + dp(6)))
        val x = when {
            moving -> movingX
            pressed -> lockedCenter - width / 2
            else -> limits.left + ((limits.right - limits.left) * (xOverride ?: options.positionX)).roundToInt()
        }.coerceIn(limits.left, limits.right)
        val y = when {
            moving -> movingY
            pressed -> startY
            else -> limits.top + ((limits.bottom - limits.top) * (yOverride ?: options.positionY)).roundToInt()
        }.coerceIn(limits.top, limits.bottom)
        val showCancel = cachedStatus.busy && cachedStatus.phase != DictationPhase.INSERTING
        val showDone = cachedStatus.phase == DictationPhase.LISTENING || cachedStatus.phase == DictationPhase.STARTING
        position(left, 0, side, height, progress, showCancel)
        position(middle, side, center, height, 1f, true)
        position(right, side + center, side, height, progress, showDone)
        middle.visualWidth = dp((visualIdle + (67 - visualIdle) * progress).roundToInt()).toFloat()
        middle.visualHeight = dp((visualIdle + (38 - visualIdle) * progress).roundToInt()).toFloat()
        left.visualHeight = dp(38).toFloat()
        left.visualWidth = dp(38).toFloat()
        right.visualHeight = left.visualHeight
        right.visualWidth = left.visualWidth
        middle.invalidate()
        params.width = width; params.height = height; params.x = x; params.y = y; params.alpha = options.opacity
        val placement = listOf(x, y, width, height)
        return try {
            if (!attached) {
                windows.addView(root, params)
                attached = true
                lastPlacement = placement; attachedAlpha = options.opacity
            } else if (placement != lastPlacement || attachedAlpha != options.opacity) {
                windows.updateViewLayout(root, params)
                lastPlacement = placement; attachedAlpha = options.opacity
            }
            true
        } catch (_: RuntimeException) { hide(); false }
    }

    @SuppressLint("RtlHardcoded")
    private fun position(button: BubbleButton, x: Int, w: Int, h: Int, alpha: Float, usable: Boolean) {
        val visible = usable && w > 0
        button.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        if (button.alpha != alpha) button.alpha = alpha
        val lp = button.layoutParams as FrameLayout.LayoutParams
        if (lp.width != w || lp.height != h || lp.leftMargin != x) {
            lp.width = w; lp.height = h; lp.leftMargin = x
            lp.gravity = Gravity.TOP or Gravity.LEFT
            button.layoutParams = lp
        }
        if (button !== middle) {
            button.isEnabled = visible && w >= dp(42)
            button.importantForAccessibility = if (button.isEnabled) View.IMPORTANT_FOR_ACCESSIBILITY_YES else View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    private fun dp(value: Int) = (context.resources.displayMetrics.density * value).roundToInt()
    private fun fraction(value: Int, min: Int, max: Int) = if (max <= min) 0f else (value - min).toFloat() / (max - min)
    @Suppress("DEPRECATION")
    private fun screen(): Rect {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = windows.currentWindowMetrics
            val inset = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            return Rect(metrics.bounds).apply { left += inset.left; top += inset.top; right -= inset.right; bottom -= inset.bottom }
        }
        val metrics = android.util.DisplayMetrics()
        windows.defaultDisplay.getMetrics(metrics)
        return Rect(0, dp(24), metrics.widthPixels, metrics.heightPixels)
    }

    private enum class Glyph { Wave, Cancel, Done, Processing, Error }

    private class BubbleButton(context: Context, var glyph: Glyph) : View(context) {
        var visualWidth = 0f
        var visualHeight = 0f
        var live = false
        var samples: FloatArray? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        init { isFocusable = true }

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            if (isClickable) info.className = android.widget.Button::class.java.name
        }
        override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val x = width / 2f
            val y = height / 2f
            val w = visualWidth.coerceAtMost(width.toFloat())
            val h = visualHeight.coerceAtMost(height.toFloat())
            if (w <= 0f || h <= 0f) return
            rect.set(x - w / 2, y - h / 2, x + w / 2, y + h / 2)
            paint.style = Paint.Style.FILL
            paint.color = when {
                isPressed -> 0xFFE3BDF2.toInt()
                glyph == Glyph.Done -> FlowCreamArgb
                else -> FlowLavenderArgb
            }
            canvas.drawRoundRect(rect, h / 2, h / 2, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = resources.displayMetrics.density * .65f
            paint.color = 0x801A1A1A.toInt()
            canvas.drawRoundRect(rect, h / 2, h / 2, paint)
            paint.style = Paint.Style.FILL
            paint.color = FlowInkArgb
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            val s = (h * .23f)
            paint.strokeWidth = (h * .045f).coerceAtLeast(1.5f)
            when (glyph) {
                Glyph.Wave -> {
                    val profile = floatArrayOf(.22f, .45f, .80f, .50f, .96f, .58f, .30f)
                    val gap = if (live) w * .084f else h * .078f
                    for (i in profile.indices) {
                        val amount = samples?.get(i)
                        val bar = if (live) .12f + profile[i] * .88f * (amount ?: 0f) else profile[i]
                        val half = bar * h * .29f
                        val cx = x + (i - 3) * gap
                        canvas.drawLine(cx, y - half, cx, y + half, paint)
                    }
                }
                Glyph.Cancel -> {
                    canvas.drawLine(x - s * .66f, y - s * .66f, x + s * .66f, y + s * .66f, paint)
                    canvas.drawLine(x + s * .66f, y - s * .66f, x - s * .66f, y + s * .66f, paint)
                }
                Glyph.Done -> {
                    canvas.drawLine(x - s * .9f, y, x - s * .3f, y + s * .60f, paint)
                    canvas.drawLine(x - s * .3f, y + s * .60f, x + s, y - s * .76f, paint)
                }
                Glyph.Error -> {
                    canvas.drawLine(x, y - s, x, y + s * .15f, paint)
                    canvas.drawCircle(x, y + s * .68f, paint.strokeWidth / 2, paint)
                }
                Glyph.Processing -> {
                    val animated = motion()
                    paint.style = Paint.Style.STROKE
                    rect.set(x - s, y - s, x + s, y + s)
                    val angle = if (animated) (SystemClock.uptimeMillis() % 1000) * .36f else 0f
                    canvas.drawArc(rect, angle - 90, 250f, false, paint)
                    if (animated && isShown) postInvalidateOnAnimation()
                }
            }
        }
    }

    private companion object {
        fun motion(): Boolean = ValueAnimator.areAnimatorsEnabled()
    }
}
