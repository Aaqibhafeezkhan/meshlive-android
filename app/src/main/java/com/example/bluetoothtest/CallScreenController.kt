package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Outline
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.math.hypot

/**
 * Presentation of the private-call screen only: which video is large, the movable
 * picture-in-picture, the call-details pill and the remote-video state labels. It never touches
 * capture, codecs, transport or the surfaces' producers; both video views keep their surfaces
 * because swapping only changes their size, position and drawing order.
 */
internal class CallScreenController(
    private val callScreen: FrameLayout,
    private val remoteContainer: FrameLayout,
    private val remoteCameraOff: TextView,
    private val remoteStateText: TextView,
    private val localContainer: FrameLayout,
    private val localViewfinder: View,
    private val localRawPreview: ImageView,
    private val topBar: ViewGroup,
    private val infoButton: ImageButton,
    private val profileText: TextView,
    private val bottomBars: List<View>,
) {
    /** What the small (or large) local view shows. */
    enum class LocalPreview { NONE, CAMERA, RAW }

    private val context = callScreen.context
    private val density = context.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val handler = Handler(Looper.getMainLooper())
    private val topBarBasePadding = topBar.paddingTop
    private val pipZ = 6f * density
    private val overlayZ = 12f * density
    private val pipCornerRadius = 12f * density
    private val pipMargin = 12f * density
    private val remoteBorder = ContextCompat.getDrawable(context, R.drawable.call_pip_border)
    private val localBorder = ContextCompat.getDrawable(context, R.drawable.call_pip_border)

    private var localPreview = LocalPreview.NONE
    private var localLarge = false
    private var corner = CallPipCorner.TOP_RIGHT
    private var insetLeft = 0
    private var insetTop = 0
    private var insetRight = 0
    private var insetBottom = 0

    private var mediaPhase = false
    private var remoteCameraEnabled = true
    private var awaitingRemoteFrame = true
    private var lastRemoteFrameNs = 0L
    private var remoteState = CallRemoteVideoState.NONE
    private var tickerRunning = false
    private var detailsVisible = false

    private val ticker = object : Runnable {
        override fun run() {
            refreshRemoteState()
            handler.postDelayed(this, REMOTE_STATE_TICK_MS)
        }
    }

    init {
        // Overlays draw above both videos; the small video draws above the large one.
        // (Plain buttons are left alone: Material buttons animate their own elevation.)
        topBar.translationZ = overlayZ
        bottomBars.filterIsInstance<ViewGroup>().forEach { it.translationZ = overlayZ }
        val outline = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val radius = if (view === smallView()) pipCornerRadius else 0f
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        listOf(remoteContainer, localContainer).forEach { container ->
            container.outlineProvider = outline
            container.clipToOutline = true
            container.setOnTouchListener(PipTouchListener())
            container.setOnClickListener { view -> if (view === smallView()) swap() }
        }
        infoButton.setOnClickListener { setDetailsVisible(!detailsVisible) }
        val relayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placePip(animate = false) }
        callScreen.addOnLayoutChangeListener(relayout)
        topBar.addOnLayoutChangeListener(relayout)
        bottomBars.forEach { it.addOnLayoutChangeListener(relayout) }
        applyArrangement()
    }

    /** System bar and display cutout insets of the call screen (the bars are hidden during calls). */
    fun setInsets(left: Int, top: Int, right: Int, bottom: Int) {
        insetLeft = left
        insetTop = top
        insetRight = right
        insetBottom = bottom
        topBar.setPadding(
            topBar.paddingLeft,
            topBarBasePadding + top,
            topBar.paddingRight,
            topBar.paddingBottom,
        )
        placePip(animate = false)
    }

    /** A new call screen: the other person's video large, no self-view yet, details hidden. */
    fun reset() {
        localLarge = false
        corner = CallPipCorner.TOP_RIGHT
        setLocalPreview(LocalPreview.NONE, VideoScaleMode.FILL)
        localRawPreview.setImageDrawable(null)
        profileText.text = ""
        infoButton.visibility = View.INVISIBLE
        setDetailsVisible(false)
        mediaPhase = false
        remoteCameraEnabled = true
        awaitingRemoteFrame = true
        lastRemoteFrameNs = 0L
        refreshRemoteState()
    }

    fun setLocalPreview(preview: LocalPreview, scaleMode: VideoScaleMode) {
        localPreview = preview
        // The viewfinder must be visible before the camera asks it for a surface.
        localViewfinder.visibility = if (preview == LocalPreview.CAMERA) View.VISIBLE else View.GONE
        localRawPreview.visibility = if (preview == LocalPreview.RAW) View.VISIBLE else View.GONE
        localRawPreview.scaleType = when (scaleMode) {
            VideoScaleMode.FILL -> ImageView.ScaleType.CENTER_CROP
            VideoScaleMode.FIT -> ImageView.ScaleType.FIT_CENTER
        }
        localContainer.visibility = if (preview == LocalPreview.NONE) View.GONE else View.VISIBLE
        if (preview == LocalPreview.NONE) {
            localRawPreview.setImageDrawable(null)
            localLarge = false
        }
        applyArrangement()
    }

    fun showRawFrame(bitmap: Bitmap) {
        if (localPreview == LocalPreview.RAW) localRawPreview.setImageBitmap(bitmap)
    }

    /** The codec/resolution pill stays hidden until the info button is tapped. */
    fun setProfileText(text: String) {
        profileText.text = text
        infoButton.visibility = View.VISIBLE
        profileText.visibility = if (detailsVisible) View.VISIBLE else View.GONE
    }

    private fun setDetailsVisible(visible: Boolean) {
        detailsVisible = visible
        profileText.visibility = if (visible && profileText.text.isNotEmpty()) View.VISIBLE else View.GONE
        infoButton.contentDescription = if (visible) "Hide call details" else "Show call details"
    }

    fun setMediaPhase(active: Boolean) {
        if (mediaPhase == active) return
        mediaPhase = active
        refreshRemoteState()
    }

    fun setRemoteCameraEnabled(enabled: Boolean) {
        remoteCameraEnabled = enabled
        if (enabled) awaitingRemoteFrame = true
        refreshRemoteState()
    }

    /** A frame reached the remote TextureView. */
    fun onRemoteFrame(nowNs: Long) {
        lastRemoteFrameNs = nowNs
        if (awaitingRemoteFrame) {
            awaitingRemoteFrame = false
            refreshRemoteState()
        } else if (remoteState == CallRemoteVideoState.FROZEN) {
            refreshRemoteState()
        }
    }

    /** Frames stop reaching the screen in the background, so freeze detection runs only in front. */
    fun setForeground(foreground: Boolean) {
        if (foreground) {
            if (lastRemoteFrameNs > 0L) lastRemoteFrameNs = System.nanoTime()
            if (!tickerRunning) {
                tickerRunning = true
                handler.post(ticker)
            }
        } else {
            tickerRunning = false
            handler.removeCallbacks(ticker)
        }
    }

    private fun refreshRemoteState() {
        val ageMs = lastRemoteFrameNs.takeIf { it > 0L }?.let { (System.nanoTime() - it) / 1_000_000L }
        val state = callRemoteVideoState(mediaPhase, remoteCameraEnabled, awaitingRemoteFrame, ageMs)
        if (state == remoteState) return
        remoteState = state
        Log.i("BluetoothVideo", "Call screen: remote video ${state.name.lowercase()}")
        // PAUSED is shown by remoteCameraOff, which the call owner sets from the peer's signal.
        val text = when (state) {
            CallRemoteVideoState.CONNECTING -> "Connecting video…"
            CallRemoteVideoState.FROZEN -> "Video frozen. Waiting for the connection…"
            CallRemoteVideoState.PAUSED, CallRemoteVideoState.NONE -> null
        }
        remoteStateText.text = text ?: ""
        remoteStateText.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private fun largeView(): FrameLayout =
        if (localLarge && localPreview != LocalPreview.NONE) localContainer else remoteContainer

    private fun smallView(): FrameLayout =
        if (largeView() === localContainer) remoteContainer else localContainer

    private fun swap() {
        if (localPreview == LocalPreview.NONE) return
        localLarge = !localLarge
        Log.i("BluetoothVideo", "Call screen: ${if (localLarge) "self-view" else "remote video"} is large")
        applyArrangement()
    }

    @SuppressLint("RtlHardcoded") // Absolute left/top: the position is set by translation.
    private fun applyArrangement() {
        val large = largeView()
        val small = smallView()
        val (width, height) = pipSize()
        large.animate().cancel()
        large.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        large.translationX = 0f
        large.translationY = 0f
        large.translationZ = 0f
        large.foreground = null
        large.isClickable = false
        small.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.TOP or Gravity.LEFT)
        small.translationZ = pipZ
        small.foreground = if (small === localContainer) localBorder else remoteBorder
        small.isClickable = true
        large.invalidateOutline()
        small.invalidateOutline()
        val remoteSmall = small === remoteContainer
        remoteContainer.contentDescription = if (remoteSmall) {
            "Other person's video. Tap to make it large, or drag to move it."
        } else {
            "Other person's video"
        }
        localContainer.contentDescription = if (!remoteSmall) {
            "Your camera preview. Tap to make it large, or drag to move it."
        } else {
            "Your camera preview"
        }
        val labelSp = if (remoteSmall) 11f else 16f
        val horizontal = ((if (remoteSmall) 8 else 16) * density).toInt()
        val vertical = ((if (remoteSmall) 5 else 10) * density).toInt()
        listOf(remoteCameraOff, remoteStateText).forEach { label ->
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, labelSp)
            label.setPadding(horizontal, vertical, horizontal, vertical)
        }
        placePip(animate = false)
    }

    private fun pipSize(): Pair<Int, Int> {
        val metrics = context.resources.displayMetrics
        val shortSide = minOf(callScreen.width, callScreen.height).takeIf { it > 0 }
            ?: minOf(metrics.widthPixels, metrics.heightPixels)
        return CallPipLayout.sizePx(shortSide, density)
    }

    private fun pipArea(): CallPipArea {
        val top = maxOf(insetTop, topBar.bottom).toFloat() + pipMargin
        val bottomLimit = bottomBars
            // Auto-hidden (INVISIBLE) bars keep their space, so the PiP never jumps when they fade.
            .filter { it.visibility != View.GONE && it.height > 0 }
            .minOfOrNull { it.top + it.paddingTop }
            ?: (callScreen.height - insetBottom)
        return CallPipArea(
            left = insetLeft + pipMargin,
            top = top,
            right = callScreen.width - insetRight - pipMargin,
            bottom = bottomLimit - pipMargin,
        )
    }

    private fun placePip(animate: Boolean) {
        if (callScreen.width == 0 || callScreen.height == 0) return
        val small = smallView()
        val params = small.layoutParams
        val expected = pipSize()
        if (params.width != expected.first || params.height != expected.second) {
            // The screen size changed (first layout, rotation, split screen): resize outside this
            // layout pass, then place.
            callScreen.post(::applyArrangement)
            return
        }
        val (x, y) = CallPipLayout.cornerPosition(corner, expected.first, expected.second, pipArea())
        if (animate) {
            small.animate().translationX(x).translationY(y)
                .setDuration(PIP_SNAP_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else if (small.translationX != x || small.translationY != y) {
            small.animate().cancel()
            small.translationX = x
            small.translationY = y
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private inner class PipTouchListener : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0f
        private var startY = 0f
        private var dragging = false
        private var velocity: VelocityTracker? = null

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            if (view !== smallView() || localPreview == LocalPreview.NONE) return false
            val width = view.width
            val height = view.height
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.animate().cancel()
                    downX = event.rawX
                    downY = event.rawY
                    startX = view.translationX
                    startY = view.translationY
                    dragging = false
                    velocity?.recycle()
                    velocity = VelocityTracker.obtain().also { track(it, event) }
                }
                MotionEvent.ACTION_MOVE -> {
                    velocity?.let { track(it, event) }
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && hypot(dx, dy) > touchSlop) {
                        dragging = true
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (dragging) {
                        val (x, y) = CallPipLayout.clampPosition(startX + dx, startY + dy, width, height, pipArea())
                        view.translationX = x
                        view.translationY = y
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val tracker = velocity
                    velocity = null
                    if (dragging) {
                        tracker?.computeCurrentVelocity(1_000)
                        corner = CallPipLayout.snapCorner(
                            view.translationX,
                            view.translationY,
                            width,
                            height,
                            pipArea(),
                            tracker?.xVelocity ?: 0f,
                            tracker?.yVelocity ?: 0f,
                        )
                        placePip(animate = true)
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        view.performClick()
                    } else {
                        placePip(animate = true)
                    }
                    tracker?.recycle()
                    dragging = false
                }
            }
            return true
        }

        /** Raw screen coordinates: the view itself moves while it is dragged. */
        private fun track(tracker: VelocityTracker, event: MotionEvent) {
            val copy = MotionEvent.obtain(event)
            copy.setLocation(event.rawX, event.rawY)
            tracker.addMovement(copy)
            copy.recycle()
        }
    }

    private companion object {
        const val REMOTE_STATE_TICK_MS = 250L
        const val PIP_SNAP_MS = 180L
    }
}
