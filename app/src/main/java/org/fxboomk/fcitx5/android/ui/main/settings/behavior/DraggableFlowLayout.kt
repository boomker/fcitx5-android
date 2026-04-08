package org.fxboomk.fcitx5.android.ui.main.settings.behavior

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A FlowLayout that supports dragging child views to reorder them.
 */
open class DraggableFlowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FlowLayout(context, attrs, defStyleAttr) {

    interface OnDragListener {
        fun onDragStarted(view: View, position: Int)
        fun onDragPositionChanged(from: Int, to: Int)
        fun onDragMoved(view: View, rawX: Float, rawY: Float) {}
        fun onDragEnded(view: View, position: Int)
    }

    var onDragListener: OnDragListener? = null

    private var dragEnabled = true
    private var isDraggingInternal = false
    private var dragView: View? = null

    val isDragging: Boolean
        get() = isDraggingInternal
    private var dragPosition = -1
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var downX = 0f
    private var downY = 0f
    private var lastSwapTime = 0L
    private val SWAP_DEBOUNCE_TIME = 100L
    private val REVERSE_SWAP_BLOCK_TIME = 300L
    private val DRAG_START_HOLD_TIME = 200L
    private var lastSwapFrom = -1
    private var lastSwapTo = -1
    private var touchSlop = 0
    private var originalDragPosition = -1
    private var suppressInternalReorder = false
    var lastTouchRawX: Float = 0f
        private set
    var lastTouchRawY: Float = 0f
        private set


    init {
        touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    }

    fun setDragEnabled(enabled: Boolean) {
        dragEnabled = enabled
    }

    fun setInternalReorderSuppressed(suppressed: Boolean) {
        suppressInternalReorder = suppressed
    }

    fun startDragForView(view: View) {
        val position = indexOfChild(view)
        if (position != -1 && !isDragging) {
            dragPosition = position
            dragView = view
            val viewLocation = IntArray(2)
            view.getLocationOnScreen(viewLocation)
            val parentLocation = IntArray(2)
            getLocationOnScreen(parentLocation)
            dragOffsetX = viewLocation[0] - parentLocation[0].toFloat()
            dragOffsetY = viewLocation[1] - parentLocation[1].toFloat()
            startDrag(view)
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!dragEnabled) return super.onInterceptTouchEvent(ev)

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dragView = findChildViewUnder(downX, downY)
                if (dragView != null) {
                    parent.requestDisallowInterceptTouchEvent(true)
                    return false
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragView != null && !isDraggingInternal) {
                    val dx = abs(ev.x - downX)
                    val dy = abs(ev.y - downY)
                    val distance = sqrt(dx * dx + dy * dy)

                    val heldDuration = ev.eventTime - ev.downTime

                    if (distance > touchSlop && heldDuration >= DRAG_START_HOLD_TIME) {
                        dragPosition = indexOfChild(dragView!!)
                        if (dragPosition != -1) {
                            val viewLocation = IntArray(2)
                            dragView!!.getLocationOnScreen(viewLocation)
                            dragOffsetX = ev.rawX - viewLocation[0]
                            dragOffsetY = ev.rawY - viewLocation[1]

                            startDrag(dragView!!)
                        }
                    }
                }
            }
        }
        return isDraggingInternal
    }

    private fun findChildViewUnder(x: Float, y: Float, excludeDragView: Boolean = false): View? {
        for (i in childCount - 1 downTo 0) {
            val child = getChildAt(i)
            if (child != null && child.visibility == View.VISIBLE && !(excludeDragView && child === dragView)) {
                val isDraggedView = child === dragView && isDraggingInternal

                val location = IntArray(2)
                child.getLocationOnScreen(location)
                val parentLocation = IntArray(2)
                getLocationOnScreen(parentLocation)

                var childLeft = location[0] - parentLocation[0]
                var childTop = location[1] - parentLocation[1]

                if (isDraggedView) {
                    childLeft += (child.x + 0.5f).toInt()
                    childTop += (child.y + 0.5f).toInt()
                }

                val childRight = childLeft + child.width
                val childBottom = childTop + child.height

                if (x >= childLeft && x < childRight && y >= childTop && y < childBottom) {
                    return child
                }
            }
        }
        return null
    }

    private fun startDrag(view: View) {
        if (isDraggingInternal) return

        isDraggingInternal = true
        dragView = view
        originalDragPosition = indexOfChild(view)
        dragPosition = originalDragPosition
        lastSwapFrom = -1
        lastSwapTo = -1
        lastSwapTime = 0L

        dragView?.alpha = 0.85f
        dragView?.translationZ = 10f

        onDragListener?.onDragStarted(view, dragPosition)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!dragEnabled || dragView == null) {
            return super.onTouchEvent(ev)
        }

        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (isDraggingInternal) {
                    lastTouchRawX = ev.rawX
                    lastTouchRawY = ev.rawY
                    val containerLocation = IntArray(2)
                    getLocationOnScreen(containerLocation)

                    val containerLeft = containerLocation[0]
                    val containerTop = containerLocation[1]
                    val containerRight = containerLeft + width
                    val containerBottom = containerTop + height

                    val isOutOfBounds = ev.rawX < containerLeft || ev.rawX > containerRight
                    if (!isOutOfBounds) {
                        val location = IntArray(2)
                        getLocationOnScreen(location)

                        dragView?.apply {
                            x = ev.rawX - dragOffsetX - location[0]
                            y = ev.rawY - dragOffsetY - location[1]
                        }
                        val dragCenterX = dragView?.let { it.x + it.width / 2f } ?: ev.x
                        val dragCenterY = dragView?.let { it.y + it.height / 2f } ?: ev.y

                        if (!suppressInternalReorder) {
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastSwapTime > SWAP_DEBOUNCE_TIME) {
                                val targetView = findChildViewUnder(dragCenterX, dragCenterY, excludeDragView = true)
                                if (targetView != null) {
                                    val targetPosition = indexOfChild(targetView)
                                    if (targetPosition != -1 && targetPosition != dragPosition) {
                                        trySwapTo(targetPosition, currentTime)
                                    }
                                } else {
                                    val closestPosition = findClosestPositionToCoordinates(dragCenterX, dragCenterY)
                                    if (closestPosition != -1 && closestPosition != dragPosition) {
                                        trySwapTo(closestPosition, currentTime)
                                    }
                                }
                            }
                        }
                        dragView?.let { onDragListener?.onDragMoved(it, ev.rawX, ev.rawY) }
                    } else {
                        val boundaryPosition = when {
                            ev.rawX < containerLeft -> 0
                            ev.rawX > containerRight -> childCount
                            else -> -1
                        }
                        if (boundaryPosition != -1 && boundaryPosition != dragPosition) {
                            trySwapTo(boundaryPosition, System.currentTimeMillis())
                        }

                        dragView?.apply {
                            x = left.toFloat()
                            y = top.toFloat()
                        }
                        dragView?.let { onDragListener?.onDragMoved(it, ev.rawX, ev.rawY) }
                    }

                    parent.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDraggingInternal) {
                    lastTouchRawX = ev.rawX
                    lastTouchRawY = ev.rawY
                    finishDrag()
                    parent.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
        }

        return true
    }
    
    private fun findClosestPositionToCoordinates(x: Float, y: Float): Int {
        // 首先尝试找到与拖拽位置在同一行的 chip
        // 使用水平中线判断，而不是欧几里得距离

        // 获取拖拽视图的垂直中心（相对于父容器）
        val dragViewCenterY = (dragView?.y ?: 0f) + (dragView?.height ?: 0) / 2

        // 找到与拖拽视图在同一行的所有 chip
        val sameRowChildren = mutableListOf<Pair<Int, View>>()
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child === dragView) continue

            val childCenterY = child.y + child.height / 2

            // 判断是否在同一行：垂直中心距离小于 chip 高度的 0.6 倍
            if (kotlin.math.abs(dragViewCenterY - childCenterY) < child.height * 0.6f) {
                sameRowChildren.add(i to child)
            }
        }

        if (sameRowChildren.isNotEmpty()) {
            // 在同一行内查找目标位置
            // 按 x 坐标排序
            sameRowChildren.sortBy { it.second.x }

            // 查找拖拽位置应该插入的位置
            // 逻辑：找到第一个中心点在拖拽位置右侧的 chip，插入到它前面
            // 如果拖拽位置在所有 chip 的右侧，插入到最后
            for ((_, pair) in sameRowChildren.withIndex()) {
                val (originalIndex, child) = pair
                val childCenterX = child.x + child.width / 2

                // 如果拖拽位置在 chip 中心的左侧，插入到这个位置
                if (x < childCenterX) {
                    return originalIndex
                }
            }

            // 如果拖拽位置在所有 chip 的右侧，插入到最后一个 chip 之后
            val lastChildIndexInOriginal = sameRowChildren.last().first
            return lastChildIndexInOriginal + 1
        }

        // 如果没有找到同一行的 chip，使用原来的逻辑
        var closestDistance = Float.MAX_VALUE
        var closestPosition = -1

        for (i in 0 until childCount) {
            if (getChildAt(i) === dragView) continue

            val location = IntArray(2)
            getChildAt(i).getLocationOnScreen(location)
            val parentLocation = IntArray(2)
            getLocationOnScreen(parentLocation)

            val childCenterX = location[0] - parentLocation[0] + getChildAt(i).width / 2
            val childCenterY = location[1] - parentLocation[1] + getChildAt(i).height / 2

            val dx = x - childCenterX
            val dy = y - childCenterY
            val distance = sqrt((dx * dx + dy * dy))

            if (distance < closestDistance) {
                closestDistance = distance
                closestPosition = i
            }
        }

        if (closestPosition == -1) {
            if (childCount > 0) {
                val firstChild = getChildAt(0)
                val location = IntArray(2)
                firstChild.getLocationOnScreen(location)
                val parentLocation = IntArray(2)
                getLocationOnScreen(parentLocation)

                val firstChildLeft = location[0] - parentLocation[0]
                val firstChildTop = location[1] - parentLocation[1]
                val firstChildBottom = firstChildTop + firstChild.height

                if (y >= firstChildTop && y <= firstChildBottom && x < firstChildLeft) {
                    return 0
                }

                val lastChild = getChildAt(childCount - 1)
                val lastLocation = IntArray(2)
                lastChild.getLocationOnScreen(lastLocation)

                val lastChildRight = (lastLocation[0] - parentLocation[0]) + lastChild.width
                val lastChildTop = lastLocation[1] - parentLocation[1]
                val lastChildBottom = lastChildTop + lastChild.height

                if (y >= lastChildTop && y <= lastChildBottom && x > lastChildRight) {
                    return childCount
                }
            }

            for (i in 0..childCount) {
                if (i == 0) {
                    val firstChild = if (childCount > 0) getChildAt(0) else null
                    if (firstChild != null) {
                        val location = IntArray(2)
                        firstChild.getLocationOnScreen(location)
                        val parentLocation = IntArray(2)
                        getLocationOnScreen(parentLocation)

                        val firstChildLeft = location[0] - parentLocation[0]
                        val firstChildTop = location[1] - parentLocation[1]
                        val firstChildBottom = firstChildTop + firstChild.height

                        if (y >= firstChildTop && y <= firstChildBottom && x < firstChildLeft) {
                            return 0
                        }
                    }
                } else {
                    val prevChild = getChildAt(i - 1)
                    val nextChild = if (i < childCount) getChildAt(i) else null

                    val prevLocation = IntArray(2)
                    prevChild.getLocationOnScreen(prevLocation)
                    val parentLocation = IntArray(2)
                    getLocationOnScreen(parentLocation)

                    val prevRight = (prevLocation[0] - parentLocation[0]) + prevChild.width
                    val prevTop = prevLocation[1] - parentLocation[1]
                    val prevBottom = prevTop + prevChild.height

                    var isValidPosition = false
                    if (nextChild == null) {
                        isValidPosition = y >= prevTop && y <= prevBottom
                    } else {
                        val nextLocation = IntArray(2)
                        nextChild.getLocationOnScreen(nextLocation)

                        val nextLeft = nextLocation[0] - parentLocation[0]
                        val nextTop = nextLocation[1] - parentLocation[1]
                        val nextBottom = nextTop + nextChild.height

                        val sameRow = (y >= minOf(prevTop, nextTop) && y <= maxOf(prevBottom, nextBottom))
                        val inPrevRange = (y >= prevTop && y <= prevBottom)

                        isValidPosition = sameRow || inPrevRange
                    }

                    if (isValidPosition && x > prevLocation[0] - parentLocation[0]) {
                        return i
                    }
                }
            }
        }

        return closestPosition
    }

    private fun trySwapTo(targetPosition: Int, now: Long): Boolean {
        val from = dragPosition
        if (from < 0 || targetPosition < 0 || targetPosition == from) return false

        val isImmediateReverse = from == lastSwapTo && targetPosition == lastSwapFrom
        if (isImmediateReverse && (now - lastSwapTime) < REVERSE_SWAP_BLOCK_TIME) {
            return false
        }

        swapViews(from, targetPosition)

        lastSwapFrom = from
        lastSwapTo = dragPosition
        lastSwapTime = now
        return true
    }

    
    private fun swapViews(from: Int, to: Int) {
        if (from == to || from < 0 || to < 0 || from >= childCount || to > childCount) return

        val viewToMove = getChildAt(from)
        if (viewToMove != null) {
            val layoutParams = viewToMove.layoutParams

            removeViewAt(from)

            val insertionIndex = when {
                to >= childCount + 1 -> childCount
                to > from -> to - 1
                else -> to
            }

            val savedX = dragView?.x
            val savedY = dragView?.y

            addView(viewToMove, insertionIndex, layoutParams)

            invalidate()

            dragPosition = insertionIndex

            if (savedX != null && savedY != null) {
                dragView?.x = savedX
                dragView?.y = savedY
            }

            onDragListener?.onDragPositionChanged(from, insertionIndex)
        }
    }

    private fun finishDrag() {
        isDraggingInternal = false
        val view = dragView

        view?.apply {
            animate()
                .alpha(1.0f)
                .translationZ(0f)
                .setDuration(250)
                .withEndAction {
                    requestLayout()
                    invalidate()
                }
                .start()
        }

        val finalPosition = view?.let { indexOfChild(it) }?.takeIf { it >= 0 }
            ?: if (dragPosition >= 0 && dragPosition < childCount) dragPosition else originalDragPosition

        if (view != null) {
            onDragListener?.onDragEnded(view, finalPosition)
        }

        dragView = null
        dragPosition = -1
        suppressInternalReorder = false

        parent.requestDisallowInterceptTouchEvent(false)
    }

}
