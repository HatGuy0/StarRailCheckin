package com.mh.starrailcheckin

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import com.yalantis.ucrop.UCropActivity

/**
 * UCrop 裁剪页子类。
 *
 * 背景：UCrop 2.2.9 是旧 View 库，工具栏布局未处理状态栏 inset；在 targetSdk 36
 * 强制 edge-to-edge 下，工具栏顶到屏幕最上方，标题与确认/取消按钮被状态栏压住。
 *
 * 修复：不按资源包名反查 id（库资源合并后按库包名 getIdentifier 会查不到），
 * 改为从 decorView 遍历找到 Toolbar，整体下移一个状态栏高度（topMargin），
 * 让标题与右侧确认按钮落在状态栏下方；顶部状态栏区由 UCrop 设置的状态栏色/根背景填充。
 */
class CropActivity : UCropActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBarHeight = if (resId != 0) resources.getDimensionPixelSize(resId) else 0
        if (statusBarHeight <= 0) return

        val toolbar = findToolbar(window.decorView) ?: return
        (toolbar.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
            lp.topMargin = statusBarHeight
            toolbar.layoutParams = lp
        }
    }

    private fun findToolbar(root: View): Toolbar? {
        if (root is Toolbar) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findToolbar(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }
}
