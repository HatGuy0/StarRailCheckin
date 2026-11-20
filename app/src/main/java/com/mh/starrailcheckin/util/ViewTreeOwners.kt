package com.mh.starrailcheckin.util

import android.view.View
import androidx.lifecycle.LifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import com.mh.starrailcheckin.R

/**
 * 离线编译环境下 androidx 的 `ViewTreeLifecycleOwner` / `ViewTreeSavedStateRegistryOwner`
 * 符号未进入 Kotlin 编译类路径，这里用与 androidx 相同的资源 id 作为 `View.setTag` 的键，
 * 手写等价写入；ComposeView 内部仍通过 androidx 的 `get(view)` 沿视图树读取同一 id 的 tag，
 * 资源合并后两边映射到同一个 int，因此绑定关系可被 Compose 识别。
 */
object ViewTreeOwners {

    /** 写入 LifecycleOwner，对应 androidx ViewTreeLifecycleOwner.set(view, owner)。 */
    fun setLifecycleOwner(view: View, owner: LifecycleOwner?) {
        view.setTag(R.id.view_tree_lifecycle_owner, owner)
    }

    /** 写入 SavedStateRegistryOwner，对应 androidx ViewTreeSavedStateRegistryOwner.set(view, owner)。 */
    fun setSavedStateRegistryOwner(view: View, owner: SavedStateRegistryOwner?) {
        view.setTag(R.id.view_tree_saved_state_registry_owner, owner)
    }
}
