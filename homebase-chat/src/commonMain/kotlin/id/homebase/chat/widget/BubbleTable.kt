package id.homebase.chat.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownComponents
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.elements.MarkdownTableBasicText
import org.intellij.markdown.MarkdownElementTypes.IMAGE
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes.HEADER
import org.intellij.markdown.flavours.gfm.GFMElementTypes.ROW
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL

private val MaxColumnWidth = 240.dp

@Composable
internal fun BubbleTable(content: String, node: ASTNode, style: TextStyle) {
    val tableRows = remember(node) { node.children.filter { it.type == HEADER || it.type == ROW } }
    val rows = remember(tableRows) { tableRows.map { row -> row.children.filter { it.type == CELL } } }
    val columns = rows.maxOfOrNull { it.size } ?: 0
    if (columns == 0) return

    val colors = LocalMarkdownColors.current
    val dimens = LocalMarkdownDimens.current
    val components = LocalMarkdownComponents.current
    val annotator = annotatorSettings()
    val shape = RoundedCornerShape(dimens.tableCornerSize)
    val divider = colors.dividerColor
    val lastRow = rows.lastIndex

    Box(
        Modifier
            .clip(shape)
            .background(colors.tableBackground, shape)
            .horizontalScroll(rememberScrollState()),
    ) {
        Layout(
            content = {
                rows.forEachIndexed { r, cells ->
                    val cellStyle = if (tableRows[r].type == HEADER) {
                        style.copy(fontWeight = FontWeight.Bold)
                    } else style
                    for (c in 0 until columns) {
                        val cell = cells.getOrNull(c)
                        Box(
                            Modifier
                                .drawBehind {
                                    if (r < lastRow) {
                                        drawLine(divider, Offset(0f, size.height), Offset(size.width, size.height))
                                    }
                                }
                                .padding(dimens.tableCellPadding),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (cell != null) {
                                if (cell.children.any { it.type == IMAGE }) {
                                    MarkdownElement(cell, components, content, includeSpacer = false)
                                } else {
                                    MarkdownTableBasicText(
                                        content = content,
                                        cell = cell,
                                        style = cellStyle,
                                        maxLines = Int.MAX_VALUE,
                                        overflow = TextOverflow.Clip,
                                        annotatorSettings = annotator,
                                    )
                                }
                            }
                        }
                    }
                }
            },
        ) { measurables, constraints ->
            val maxCol = MaxColumnWidth.roundToPx()
            val colWidths = IntArray(columns)
            measurables.forEachIndexed { i, m ->
                val c = i % columns
                colWidths[c] = maxOf(colWidths[c], minOf(maxCol, m.maxIntrinsicWidth(Constraints.Infinity)))
            }
            val rowHeights = IntArray(rows.size)
            measurables.forEachIndexed { i, m ->
                val r = i / columns
                rowHeights[r] = maxOf(rowHeights[r], m.minIntrinsicHeight(colWidths[i % columns]))
            }
            val placeables = measurables.mapIndexed { i, m ->
                m.measure(Constraints.fixed(colWidths[i % columns], rowHeights[i / columns]))
            }
            val width = colWidths.sum().coerceIn(constraints.minWidth, constraints.maxWidth)
            val height = rowHeights.sum().coerceIn(constraints.minHeight, constraints.maxHeight)
            layout(width, height) {
                var y = 0
                for (r in rows.indices) {
                    var x = 0
                    for (c in 0 until columns) {
                        placeables[r * columns + c].placeRelative(x, y)
                        x += colWidths[c]
                    }
                    y += rowHeights[r]
                }
            }
        }
    }
}
