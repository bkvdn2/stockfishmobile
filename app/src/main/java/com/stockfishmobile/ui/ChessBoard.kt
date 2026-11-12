package com.stockfishmobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.stockfishmobile.R
import com.stockfishmobile.chess.Move
import com.stockfishmobile.chess.PieceType
import com.stockfishmobile.chess.Position
import com.stockfishmobile.chess.Side
import com.stockfishmobile.chess.Sq
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

data class BoardArrow(val from: Int, val to: Int, val color: Color, val widthFraction: Float = 0.16f)

@Composable
fun rememberPieceImages(): Map<Pair<PieceType, Side>, ImageBitmap> {
    val ids = mapOf(
        (PieceType.KING to Side.WHITE) to R.drawable.piece_wk,
        (PieceType.QUEEN to Side.WHITE) to R.drawable.piece_wq,
        (PieceType.ROOK to Side.WHITE) to R.drawable.piece_wr,
        (PieceType.BISHOP to Side.WHITE) to R.drawable.piece_wb,
        (PieceType.KNIGHT to Side.WHITE) to R.drawable.piece_wn,
        (PieceType.PAWN to Side.WHITE) to R.drawable.piece_wp,
        (PieceType.KING to Side.BLACK) to R.drawable.piece_bk,
        (PieceType.QUEEN to Side.BLACK) to R.drawable.piece_bq,
        (PieceType.ROOK to Side.BLACK) to R.drawable.piece_br,
        (PieceType.BISHOP to Side.BLACK) to R.drawable.piece_bb,
        (PieceType.KNIGHT to Side.BLACK) to R.drawable.piece_bn,
        (PieceType.PAWN to Side.BLACK) to R.drawable.piece_bp,
    )
    return ids.mapValues { ImageBitmap.imageResource(it.value) }
}

@Composable
fun ChessBoard(
    position: Position,
    flipped: Boolean,
    lastMove: Move?,
    selected: Int?,
    targets: Set<Int>,
    arrows: List<BoardArrow>,
    squareHighlights: Map<Int, Color>,
    showCoordinates: Boolean,
    onTap: (Int) -> Unit,
    onDragStart: (Int) -> Unit,
    onDrop: (Int, Int) -> Unit,
    canDrag: (Int) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val images = rememberPieceImages()
    val textMeasurer = rememberTextMeasurer()
    var dragFrom by remember { mutableStateOf<Int?>(null) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }
    val currentFlipped by rememberUpdatedState(flipped)
    val tap by rememberUpdatedState(onTap)
    val dragStart by rememberUpdatedState(onDragStart)
    val drop by rememberUpdatedState(onDrop)
    val dragAllowed by rememberUpdatedState(canDrag)

    fun squareAt(offset: Offset, boardSize: Float): Int? {
        val sq = boardSize / 8f
        val col = (offset.x / sq).toInt()
        val row = (offset.y / sq).toInt()
        if (col !in 0..7 || row !in 0..7) return null
        return if (!currentFlipped) Sq.of(col, 7 - row) else Sq.of(7 - col, row)
    }

    Canvas(
        modifier = modifier
            .aspectRatio(1f)
            .pointerInput(Unit) {
                detectTapGestures { off ->
                    squareAt(off, size.width.toFloat())?.let { tap(it) }
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { off ->
                        val sq = squareAt(off, size.width.toFloat())
                        if (sq != null && dragAllowed(sq)) {
                            dragFrom = sq
                            dragPos = off
                            dragStart(sq)
                        }
                    },
                    onDrag = { change, amount ->
                        if (dragFrom != null) {
                            change.consume()
                            dragPos += amount
                        }
                    },
                    onDragEnd = {
                        val from = dragFrom
                        dragFrom = null
                        if (from != null) {
                            val to = squareAt(dragPos, size.width.toFloat())
                            if (to != null) drop(from, to) else drop(from, from)
                        }
                    },
                    onDragCancel = { dragFrom = null },
                )
            },
    ) {
        val boardSize = min(size.width, size.height)
        val sq = boardSize / 8f

        fun topLeft(square: Int): Offset {
            val f = Sq.file(square)
            val r = Sq.rank(square)
            val col = if (!flipped) f else 7 - f
            val row = if (!flipped) 7 - r else r
            return Offset(col * sq, row * sq)
        }

        fun center(square: Int): Offset = topLeft(square) + Offset(sq / 2, sq / 2)

        // Squares
        for (s in 0 until 64) {
            val light = (Sq.file(s) + Sq.rank(s)) % 2 == 1
            drawRect(if (light) Palette.lightSquare else Palette.darkSquare, topLeft(s), Size(sq, sq))
        }
        // Last move
        lastMove?.let {
            drawRect(Palette.lastMove, topLeft(it.from), Size(sq, sq))
            drawRect(Palette.lastMove, topLeft(it.to), Size(sq, sq))
        }
        for ((s, c) in squareHighlights) drawRect(c, topLeft(s), Size(sq, sq))
        selected?.let { drawRect(Palette.selected, topLeft(it), Size(sq, sq)) }

        // King in check
        if (position.isCheck) {
            val k = position.kingSquare(position.sideToMove)
            if (k >= 0) {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Palette.check, Palette.check.copy(alpha = 0f)),
                        center = center(k),
                        radius = sq * 0.7f,
                    ),
                    radius = sq * 0.7f,
                    center = center(k),
                )
            }
        }

        if (showCoordinates) drawCoordinates(sq, flipped, textMeasurer)

        // Pieces
        for (s in 0 until 64) {
            if (s == dragFrom) continue
            val p = position[s] ?: continue
            val img = images[p.type to p.side] ?: continue
            drawPiece(img, topLeft(s), sq)
        }

        // Legal move hints (above pieces so captures are visible)
        for (t in targets) {
            if (position[t] != null) {
                drawCircle(Palette.legalDot, radius = sq * 0.46f, center = center(t), style = Stroke(width = sq * 0.08f))
            } else {
                drawCircle(Palette.legalDot, radius = sq * 0.15f, center = center(t))
            }
        }

        // Arrows
        for (a in arrows) drawArrow(center(a.from), center(a.to), sq, a.color, a.widthFraction)

        // Dragged piece follows the finger, slightly enlarged.
        dragFrom?.let { from ->
            val p = position[from]
            val img = p?.let { images[it.type to it.side] }
            if (img != null) {
                val big = sq * 1.35f
                drawPiece(img, dragPos - Offset(big / 2, big * 0.75f), big)
            }
        }
    }
}

private fun DrawScope.drawPiece(img: ImageBitmap, topLeft: Offset, size: Float) {
    drawImage(
        image = img,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(img.width, img.height),
        dstOffset = IntOffset(topLeft.x.toInt(), topLeft.y.toInt()),
        dstSize = IntSize(size.toInt(), size.toInt()),
    )
}

private fun DrawScope.drawCoordinates(sq: Float, flipped: Boolean, tm: androidx.compose.ui.text.TextMeasurer) {
    val fontSize = (sq * 0.2f).toSp()
    for (i in 0..7) {
        // Files along the bottom edge
        val file = if (!flipped) i else 7 - i
        val fileSquareLight = (file + (if (!flipped) 0 else 7)) % 2 == 1
        val fileColor = if (fileSquareLight) Palette.darkSquare else Palette.lightSquare
        val fl = tm.measure("${'a' + file}", TextStyle(fontSize = fontSize, fontWeight = FontWeight.SemiBold, color = fileColor))
        drawText(fl, topLeft = Offset((i + 1) * sq - fl.size.width - sq * 0.05f, 8 * sq - fl.size.height))
        // Ranks along the left edge
        val rank = if (!flipped) 7 - i else i
        val rankSquareLight = ((if (!flipped) 0 else 7) + rank) % 2 == 1
        val rankColor = if (rankSquareLight) Palette.darkSquare else Palette.lightSquare
        val rl = tm.measure("${rank + 1}", TextStyle(fontSize = fontSize, fontWeight = FontWeight.SemiBold, color = rankColor))
        drawText(rl, topLeft = Offset(sq * 0.05f, i * sq + sq * 0.02f))
    }
}

private fun DrawScope.drawArrow(from: Offset, to: Offset, sq: Float, color: Color, widthFraction: Float) {
    val angle = atan2(to.y - from.y, to.x - from.x)
    val width = sq * widthFraction
    val headLen = sq * 0.42f
    val headWidth = sq * 0.42f
    val end = Offset(to.x - cos(angle) * headLen * 0.9f, to.y - sin(angle) * headLen * 0.9f)
    val start = Offset(from.x + cos(angle) * sq * 0.18f, from.y + sin(angle) * sq * 0.18f)
    drawLine(color, start, end, strokeWidth = width, cap = StrokeCap.Butt)
    val tip = Offset(to.x - cos(angle) * sq * 0.08f, to.y - sin(angle) * sq * 0.08f)
    val base = Offset(tip.x - cos(angle) * headLen, tip.y - sin(angle) * headLen)
    val perp = Offset(-sin(angle), cos(angle))
    val path = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(base.x + perp.x * headWidth / 2, base.y + perp.y * headWidth / 2)
        lineTo(base.x - perp.x * headWidth / 2, base.y - perp.y * headWidth / 2)
        close()
    }
    drawPath(path, color)
}

