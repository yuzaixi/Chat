package com.huidiandian.meeting.mcu.view

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

class MeetVideoControl : FrameLayout {

    constructor(context: Context) : this(context, null)

    constructor(context: Context, attributeSet: AttributeSet?) : this(context, attributeSet, -1)

    constructor(context: Context, attributeSet: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attributeSet,
        defStyleAttr
    ) {

    }

    companion object {
        private const val TAG = "MeetVideoControl"
    }
}