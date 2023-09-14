package gui

import freditor.Fronts
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import javax.swing.JComponent

private val EMPTY_STACK = IntArray(0)

class StackTable : JComponent() {
    private var stack = EMPTY_STACK

    init {
        resize(0)
    }

    private fun resize(rows: Int) {
        val dimension = Dimension(Fronts.front.width * 5, Fronts.front.height * rows)
        minimumSize = dimension
        maximumSize = dimension
        preferredSize = dimension
        revalidate()
    }

    fun clearStack() {
        stack = EMPTY_STACK
    }

    fun setStack(stack: IntArray) {
        if (stack !== this.stack) {
            val newSize = stack.size
            val oldSize = this.stack.size
            this.stack = stack
            if (newSize != oldSize) {
                resize(newSize)
            }
            repaint()
        }
    }

    override fun paint(g: Graphics) {
        g.color = Color.WHITE
        g.fillRect(0, 0, width, height)

        val frontHeight = Fronts.front.height
        var y = stack.size * frontHeight

        for (value in stack) {
            y -= frontHeight
            when (value) {
                0 -> Fronts.front.drawString(g, 0, y, "false", 0xff0000)
                -1 -> Fronts.front.drawString(g, 0, y, "true", 0x008000)

                in 1..255 -> Fronts.front.drawString(g, 0, y, "%4d".format(value), 0x6400c8)

                else -> Fronts.front.drawString(g, 0, y, "%4x".format(value), 0x808080)
            }
        }
    }
}
