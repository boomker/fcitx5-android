/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.adapter

import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.main.settings.behavior.DraggableFlowLayout
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.views.backgroundColor

/**
 * RecyclerView adapter for displaying and editing keyboard layout rows and keys.
 *
 * Features:
 * - Display rows and keys list
 * - Support row drag-to-reorder
 * - Support key drag-to-reorder
 * - Add/remove rows and keys
 */
class KeyboardLayoutAdapter(
    private val context: Context,
    private var rows: List<MutableList<MutableMap<String, Any?>>>,
    internal val listener: Listener
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    // These are set via setupRowDragTrigger
    private var rowsRecyclerView: RecyclerView? = null
    private var rowTouchHelper: ItemTouchHelper? = null

    /**
     * Setup row drag trigger - must be called after adapter is created
     */
    fun setupRowDragTrigger(recyclerView: RecyclerView, touchHelper: ItemTouchHelper?) {
        rowsRecyclerView = recyclerView
        rowTouchHelper = touchHelper
    }

    interface Listener {
        /** Called when a key is clicked */
        fun onKeyClick(rowIndex: Int, keyIndex: Int)

        /** Called when the add key button is clicked */
        fun onAddKeyClick(rowIndex: Int)

        /** Called when the delete row button is clicked */
        fun onDeleteRowClick(rowIndex: Int)

        /** Called when the add row button is clicked */
        fun onAddRowClick()

        /** Called when a row drag position changes */
        fun onRowPositionChanged(from: Int, to: Int)

        /** Called when a row drag ends */
        fun onRowDragEnded()

        /** Called when a key drag position changes */
        fun onKeyPositionChanged(rowIndex: Int, from: Int, to: Int)

        /** Called when a key drag ends */
        fun onKeyDragEnded(rowIndex: Int)
    }

    private companion object {
        private const val VIEW_TYPE_ROW = 0
        private const val VIEW_TYPE_ADD_ROW = 1
    }

    /**
     * Update rows data.
     * Posts the update to avoid calling notifyDataSetChanged() during RecyclerView layout/scroll.
     */
    fun updateRows(newRows: List<MutableList<MutableMap<String, Any?>>>) {
        rows = newRows
        rowsRecyclerView?.post { notifyDataSetChanged() }
    }

    /**
     * Notify that a single key has changed (call after modifying key data).
     * Uses notifyItemChanged to trigger RecyclerView to re-measure the row (handles key size changes).
     */
    fun notifyKeyChanged(rowIndex: Int, keyIndex: Int) {
        notifyItemChanged(rowIndex)
    }

    /**
     * Notify that an entire row has changed (call after adding/removing keys).
     */
    fun notifyRowChanged(rowIndex: Int) {
        notifyItemChanged(rowIndex)
    }

    /**
     * Notify that a new row has been inserted.
     */
    fun notifyRowInserted(position: Int) {
        notifyItemInserted(position)
        // Notify that the "add row" button position has changed
        notifyItemChanged(itemCount - 1)
    }

    /**
     * Notify that a row has been removed.
     */
    fun notifyRowRemoved(position: Int) {
        notifyItemRemoved(position)
        // Notify that the "add row" button position has changed
        notifyItemChanged(itemCount - 1)
    }

    /**
     * Notify that a row has been moved (drag-to-reorder).
     */
    fun notifyRowMoved(fromPosition: Int, toPosition: Int) {
        notifyItemMoved(fromPosition, toPosition)
    }

    override fun getItemViewType(position: Int): Int {
        return if (position == rows.size) VIEW_TYPE_ADD_ROW else VIEW_TYPE_ROW
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_ROW -> createRowViewHolder(parent)
            VIEW_TYPE_ADD_ROW -> createAddRowViewHolder(parent)
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    private fun createRowViewHolder(parent: ViewGroup): RowViewHolder {
        val rowContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, context.dp(4), 0, context.dp(4))
        }

        // Drag handle on the left
        val dragHandle = TextView(context).apply {
            text = "☰"
            textSize = 16f
            setPadding(context.dp(8), context.dp(8), context.dp(4), context.dp(8))
            setTextColor(context.styledColor(android.R.attr.textColorSecondary))
            alpha = 0.5f
            minWidth = context.dp(32)
        }
        rowContainer.addView(dragHandle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_VERTICAL
        })

        // FlowLayout for keys with auto-wrap
        val keysFlowContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                weight = 1f
            }
        }

        val keysFlow = DraggableFlowLayout(context).apply {
            setPadding(0, context.dp(4), 0, context.dp(4))
        }
        keysFlowContainer.addView(keysFlow)
        rowContainer.addView(keysFlowContainer)

        // Delete row button on the right
        val deleteRowButton = TextView(context).apply {
            text = "🗑"
            textSize = 14f
            setPadding(context.dp(8), context.dp(8), context.dp(8), context.dp(8))
            minWidth = context.dp(36)
        }
        rowContainer.addView(deleteRowButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_VERTICAL
        })

        val viewHolder = RowViewHolder(rowContainer, dragHandle, keysFlow, deleteRowButton)
        // Setup listeners for fixed parts (called only once)
        setupRowViewHolder(viewHolder)
        return viewHolder
    }

    private fun createAddRowViewHolder(parent: ViewGroup): AddRowViewHolder {
        val addRowButton = TextView(context).apply {
            text = context.getString(R.string.text_keyboard_layout_add_row)
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(context.dp(14), context.dp(10), context.dp(14), context.dp(10))
            minWidth = context.dp(160)
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(context.styledColor(android.R.attr.colorPrimary))
                setStroke(context.dp(1), context.styledColor(android.R.attr.colorControlNormal))
                cornerRadius = context.dp(4).toFloat()
            }
            setOnClickListener { listener.onAddRowClick() }
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, context.dp(16), 0, context.dp(8))
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            )
            addView(addRowButton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        return AddRowViewHolder(container, addRowButton)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is RowViewHolder -> {
                // Rebind keys (listeners for fixed parts like delete button are already set in createRowViewHolder)
                bindRowKeys(holder, position)
            }
            is AddRowViewHolder -> {
                // Add row button doesn't need binding, click listener is set in onCreateViewHolder
            }
        }
    }

    /**
     * Setup fixed parts of RowViewHolder (drag handle, delete button, etc.).
     * Called only once during creation, uses bindingAdapterPosition to get current position dynamically.
     */
    private fun setupRowViewHolder(holder: RowViewHolder) {
        // Delete button click listener - uses bindingAdapterPosition to get current position dynamically
        holder.deleteButton.setOnClickListener {
            val adapterPosition = holder.bindingAdapterPosition
            if (adapterPosition != RecyclerView.NO_POSITION) {
                listener.onDeleteRowClick(adapterPosition)
            }
        }

        var downOnKeyChip = false
        val startRowDragIfAllowed: () -> Boolean = {
            if (holder.keysFlow is DraggableFlowLayout && holder.keysFlow.isDragging) {
                false
            } else {
                val adapterPosition = holder.bindingAdapterPosition
                if (adapterPosition != RecyclerView.NO_POSITION) {
                    rowsRecyclerView?.findViewHolderForAdapterPosition(adapterPosition)?.let { viewHolder ->
                        rowTouchHelper?.startDrag(viewHolder)
                        true
                    } ?: false
                } else {
                    false
                }
            }
        }

        // Setup drag handle for row reordering (only if not dragging a key)
        holder.dragHandle.setOnLongClickListener {
            startRowDragIfAllowed()
        }

        // Allow row drag when long-press starts in keysFlow blank area
        holder.keysFlow.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downOnKeyChip = holder.keysFlow.isTouchOnAnyChild(event.x, event.y)
                }
            }
            false
        }
        holder.keysFlow.setOnLongClickListener {
            if (downOnKeyChip) false else startRowDragIfAllowed()
        }

        // Add touch feedback for drag handle
        holder.dragHandle.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.alpha = 0.3f
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 0.5f
                }
            }
            false
        }
    }

    /**
     * Bind keys in a row (can be called multiple times for partial refresh).
     */
    private fun bindRowKeys(holder: RowViewHolder, position: Int) {
        val row = rows[position]
        holder.keysFlow.removeAllViews()

        // Setup drag listener for key reordering (once per row, not per key)
        if (holder.keysFlow is DraggableFlowLayout && holder.keysFlow.onDragListener == null) {
            holder.keysFlow.onDragListener = object : DraggableFlowLayout.OnDragListener {
                override fun onDragStarted(view: View, position: Int) {
                }

                override fun onDragPositionChanged(from: Int, to: Int) {
                    val adapterPosition = holder.bindingAdapterPosition
                    if (adapterPosition != RecyclerView.NO_POSITION) {
                        listener.onKeyPositionChanged(adapterPosition, from, to)
                    }
                }

                override fun onDragEnded(view: View, position: Int) {
                    val adapterPosition = holder.bindingAdapterPosition
                    if (adapterPosition != RecyclerView.NO_POSITION) {
                        listener.onKeyDragEnded(adapterPosition)
                    }
                }
            }
        }

        // Add keys - short click to edit, with drag support (directly on the key)
        row.forEachIndexed { keyIndex, key ->
            val keyChip = TextView(context).apply {
                text = buildKeyLabel(key)
                textSize = 14f
                setPadding(context.dp(10), context.dp(8), context.dp(10), context.dp(8))
                gravity = Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(context.styledColor(android.R.attr.colorButtonNormal))
                    setStroke(context.dp(1), context.styledColor(android.R.attr.colorControlNormal))
                    cornerRadius = context.dp(4).toFloat()
                }
                setOnClickListener {
                    val adapterPosition = holder.bindingAdapterPosition
                    if (adapterPosition != RecyclerView.NO_POSITION) {
                        listener.onKeyClick(adapterPosition, keyIndex)
                    }
                }
            }

            holder.keysFlow.addView(keyChip, ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                rightMargin = context.dp(6)
                bottomMargin = context.dp(4)
                topMargin = context.dp(4)
            })
        }

        // Add button (same style as other keys)
        val addKeyChip = TextView(context).apply {
            text = "+"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(context.dp(10), context.dp(8), context.dp(10), context.dp(8))
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(context.styledColor(android.R.attr.colorPrimary))
                setStroke(context.dp(1), context.styledColor(android.R.attr.colorControlNormal))
                cornerRadius = context.dp(4).toFloat()
            }
            setOnClickListener {
                val adapterPosition = holder.bindingAdapterPosition
                if (adapterPosition != RecyclerView.NO_POSITION) {
                    listener.onAddKeyClick(adapterPosition)
                }
            }
        }
        holder.keysFlow.addView(addKeyChip, ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            rightMargin = context.dp(6)
            bottomMargin = context.dp(4)
            topMargin = context.dp(4)
        })
    }

    override fun getItemCount(): Int = rows.size + 1  // +1 for add row button

    /**
     * Build key display label
     */
    private fun buildKeyLabel(key: Map<String, Any?>): String {
        val type = key["type"] as? String ?: "?"
        return when (type) {
            "AlphabetKey" -> {
                val main = key["main"] as? String ?: ""
                main.ifEmpty { "?" }
            }
            "CapsKey" -> context.getString(R.string.text_keyboard_layout_key_label_caps)
            "LayoutSwitchKey" -> {
                val label = key["label"] as? String ?: "?123"
                val subLabel = key["subLabel"] as? String ?: ""
                if (subLabel.isNotEmpty()) "$label→$subLabel" else label
            }
            "CommaKey" -> ","
            "LanguageKey" -> context.getString(R.string.text_keyboard_layout_key_label_lang)
            "SpaceKey" -> context.getString(R.string.text_keyboard_layout_key_label_space)
            "SymbolKey" -> key["label"] as? String ?: "."
            "ReturnKey" -> context.getString(R.string.text_keyboard_layout_key_label_enter)
            "BackspaceKey" -> "⌫"
            else -> type
        }
    }

    /**
     * Check if touch point is on any child view
     */
    private fun ViewGroup.isTouchOnAnyChild(x: Float, y: Float): Boolean {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            if (x >= child.left && x < child.right && y >= child.top && y < child.bottom) {
                return true
            }
        }
        return false
    }

    /**
     * Row ViewHolder
     */
    data class RowViewHolder(
        val container: LinearLayout,
        val dragHandle: TextView,
        val keysFlow: ViewGroup,
        val deleteButton: TextView
    ) : RecyclerView.ViewHolder(container)

    /**
     * Add row ViewHolder
     */
    data class AddRowViewHolder(
        val container: LinearLayout,
        val addRowButton: TextView
    ) : RecyclerView.ViewHolder(container)
}
