package com.firerpa.ui

import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView

object NoWrapEditorHelper {
    fun configureLogText(
        textView: TextView,
        verticalScroll: ScrollView,
        horizontalScroll: HorizontalScrollView,
    ) {
        disableScrollbars(textView)
        disableScrollbars(verticalScroll)
        disableScrollbars(horizontalScroll)
        textView.viewTreeObserver.addOnGlobalLayoutListener {
            val viewportWidth = verticalScroll.width - verticalScroll.paddingLeft - verticalScroll.paddingRight
            if (viewportWidth > 0 && textView.minimumWidth != viewportWidth) {
                textView.minWidth = viewportWidth
            }
        }
    }

    fun configureConfigEditor(
        editText: EditText,
        verticalScroll: ScrollView,
        horizontalScroll: HorizontalScrollView,
    ) {
        editText.setHorizontallyScrolling(true)
        editText.isVerticalScrollBarEnabled = false
        editText.isHorizontalScrollBarEnabled = false
        editText.overScrollMode = View.OVER_SCROLL_NEVER
        disableScrollbars(verticalScroll)
        disableScrollbars(horizontalScroll)
        editText.viewTreeObserver.addOnGlobalLayoutListener {
            val viewportWidth = verticalScroll.width - verticalScroll.paddingLeft - verticalScroll.paddingRight
            if (viewportWidth > 0 && editText.minimumWidth != viewportWidth) {
                editText.minWidth = viewportWidth
            }
        }
    }

    private fun disableScrollbars(view: View) {
        when (view) {
            is ScrollView -> {
                view.isVerticalScrollBarEnabled = false
                view.isHorizontalScrollBarEnabled = false
                view.overScrollMode = View.OVER_SCROLL_NEVER
            }
            is HorizontalScrollView -> {
                view.isHorizontalScrollBarEnabled = false
                view.isVerticalScrollBarEnabled = false
                view.overScrollMode = View.OVER_SCROLL_NEVER
            }
            is TextView -> {
                view.isVerticalScrollBarEnabled = false
                view.isHorizontalScrollBarEnabled = false
                view.overScrollMode = View.OVER_SCROLL_NEVER
            }
        }
    }
}
