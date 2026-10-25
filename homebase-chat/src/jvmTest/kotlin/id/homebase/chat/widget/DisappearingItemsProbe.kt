@file:OptIn(ExperimentalTestApi::class)

package id.homebase.chat.widget

import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher

// Reflects on foundation internals (LazyLayoutItemAnimator); a renamed member throws, never passes silently.
private fun ComposeUiTest.itemAnimators(): List<Any> =
    onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.CollectionInfo), useUnmergedTree = true)
        .fetchSemanticsNodes(atLeastOneRootRequired = false)
        .flatMap { node -> node.layoutInfo.getModifierInfo().map { it.modifier } }
        .filter { it.javaClass.name.endsWith("DisplayingDisappearingItemsElement") }
        .map { it.javaClass.getDeclaredField("animator").apply { isAccessible = true }.get(it) }
        .also { check(it.isNotEmpty()) { "no lazy list item animator on screen" } }

private fun Any.field(name: String): Any? =
    javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this)

private fun Any.getter(name: String): Any? =
    generateSequence<Class<*>>(javaClass) { it.superclass }
        .firstNotNullOf { c -> c.declaredMethods.firstOrNull { it.name == name && it.parameterCount == 0 } }
        .apply { isAccessible = true }.invoke(this)

private fun ComposeUiTest.disappearing(): List<Any> =
    itemAnimators().flatMap { it.field("disappearingItems") as List<*> }.map { it!! }

internal fun ComposeUiTest.strandedDisappearingItems(): Int =
    disappearing().count { it.getter("getLayer") == null }

internal fun ComposeUiTest.fadingOutItems(): Int =
    disappearing().count { it.getter("getLayer") != null && it.getter("isDisappearanceAnimationInProgress") == true }

internal fun ComposeUiTest.fadingInKeys(): Set<Any> =
    itemAnimators().flatMap { animator ->
        val keyToInfo = animator.field("keyToItemInfoMap")!!.getter("asMap") as Map<*, *>
        keyToInfo.filter { (_, info) ->
            (info!!.getter("getAnimations") as Array<*>).any { it?.getter("isAppearanceAnimationInProgress") == true }
        }.keys.map { it!! }
    }.toSet()

internal fun ComposeUiTest.invalidateDisappearingItemsDraw() {
    itemAnimators().forEach { animator ->
        val node = animator.field("displayingNode") as DrawModifierNode
        runOnUiThread { node.invalidateDraw() }
    }
}
