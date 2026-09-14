package com.assetstudio.mobile.ui.list

import android.content.Context
import android.content.SharedPreferences

/*
 * Asset列表分类 tab 的用户排序持久化。
 *
 * 交互：长按任一分类 tab → 该分类移到最前（「All」固定第一位不受影响）；
 * 多次长按形成顺序（最后长按的在最前），重启 app 后按保存的顺序显示。
 *
 * 存储约定：保存"当前完整显示顺序"（而不只Yes被长按过的Type），
 * 这样下次Load时任何Type集合变化（换了FileLoad）都能稳定还原：
 * 已保存的Type按保存序排，新出现的Type按默认序（数量降序）跟在后面。
 */

/** tab 顺序持久化存储（SharedPreferences） */
object TabOrderStore {

    private const val PREFS_NAME = "tab_order"
    private const val KEY_ORDER = "order"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 纯逻辑（无 Android 依赖，可单测）：把 [typeName] 移到列表最前。
     * 不存在时插入到最前（容错：保存数据与当前Type集不一致的Scene）。
     */
    internal fun moveToFront(current: List<String>, typeName: String): List<String> =
        listOf(typeName) + current.filter { it != typeName }

    /**
     * 纯逻辑（可单测）：按 [saved] 顺序重排 [types]。
     * saved 中不存在的Type按原相对顺序跟在后面（sortedWith 稳定排序）。
     */
    internal fun applyOrder(types: List<String>, saved: List<String>): List<String> {
        val idx = saved.withIndex().associate { (i, n) -> n to i }
        return types.sortedWith(compareBy { idx[it] ?: Int.MAX_VALUE })
    }

    /** 读取保存的顺序（过滤空items，保持保存顺序） */
    fun load(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_ORDER, null) ?: return emptyList()
        return raw.split('\n').filter { it.isNotBlank() }
    }

    /** 保存完整顺序 */
    fun save(context: Context, order: List<String>) {
        prefs(context).edit().putString(KEY_ORDER, order.joinToString("\n")).apply()
    }
}
