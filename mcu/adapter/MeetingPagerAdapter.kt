package com.huidiandian.meeting.mcu.adapter

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class MeetingPagerAdapter(fragmentActivity: FragmentActivity) :
    FragmentStateAdapter(fragmentActivity) {

    val fragments = mutableListOf<Fragment>()

    override fun getItemCount(): Int = fragments.size

    override fun createFragment(position: Int): Fragment {
        return fragments[position]
    }

    override fun containsItem(itemId: Long): Boolean {
        return fragments.any { it.hashCode().toLong() == itemId }
    }

    override fun getItemId(position: Int): Long {
        return fragments[position].hashCode().toLong()
    }

    fun addFragment(fragment: Fragment) {
        fragments.add(fragment)
        notifyItemInserted(fragments.size - 1)
    }

    fun removeFragment(index: Int) {
        if (fragments.size > index) {
            fragments.removeAt(index)
        }
        notifyItemRemoved(index)
    }

}