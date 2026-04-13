package com.emailchat.ui.chat

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

class IncomingBubbleShape(private val cornerRadius: Float, private val showTail: Boolean) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        return Outline.Generic(Path().apply {
            val tailWidth = if (showTail) with(density) { 6.dp.toPx() } else 0f
            val rad = cornerRadius

            // Рисуем основной прямоугольник со скругленными углами
            // Начинаем сверху слева (после хвостика)
            moveTo(tailWidth + rad, 0f)
            lineTo(size.width - rad, 0f)
            quadraticBezierTo(size.width, 0f, size.width, rad)
            lineTo(size.width, size.height - rad)
            quadraticBezierTo(size.width, size.height, size.width - rad, size.height)
            
            // Низ сообщения
            if (showTail) {
                // Идем к нижнему левому углу (где хвостик)
                lineTo(tailWidth + rad, size.height)
                // Рисуем хвостик вниз
                lineTo(0f, size.height)
                lineTo(tailWidth, size.height - 12f)
            } else {
                lineTo(tailWidth + rad, size.height)
                quadraticBezierTo(tailWidth, size.height, tailWidth, size.height - rad)
            }

            lineTo(tailWidth, rad)
            quadraticBezierTo(tailWidth, 0f, tailWidth + rad, 0f)
            close()
        })
    }
}

class OutgoingBubbleShape(private val cornerRadius: Float, private val showTail: Boolean) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        return Outline.Generic(Path().apply {
            val tailWidth = if (showTail) with(density) { 6.dp.toPx() } else 0f
            val rad = cornerRadius

            moveTo(rad, 0f)
            lineTo(size.width - tailWidth - rad, 0f)
            quadraticBezierTo(size.width - tailWidth, 0f, size.width - tailWidth, rad)
            lineTo(size.width - tailWidth, size.height - rad)
            
            if (showTail) {
                // Хвостик справа внизу
                lineTo(size.width - tailWidth, size.height - 12f)
                lineTo(size.width, size.height)
                lineTo(size.width - tailWidth - rad, size.height)
            } else {
                quadraticBezierTo(size.width - tailWidth, size.height, size.width - tailWidth - rad, size.height)
            }

            lineTo(rad, size.height)
            quadraticBezierTo(0f, size.height, 0f, size.height - rad)
            lineTo(0f, rad)
            quadraticBezierTo(0f, 0f, rad, 0f)
            close()
        })
    }
}
