package com.aelqsimi.ast.ui;

import com.intellij.ui.JBColor;

import java.awt.*;
import java.awt.geom.Path2D;

final class GraphNodeControls {
    static final int SIZE = 18;
    static final int GAP = 4;
    static final int PADDING = 5;

    private GraphNodeControls() {
    }

    static int reservedWidth() {
        return SIZE * 2 + GAP + PADDING;
    }

    static Rectangle bounds(Action action, int nodeX, int nodeY, int nodeWidth) {
        int navigateX = nodeX + nodeWidth - PADDING - SIZE;
        int x = action == Action.NAVIGATE ? navigateX : navigateX - GAP - SIZE;
        return new Rectangle(x, nodeY + PADDING, SIZE, SIZE);
    }

    static Action actionAt(Point point, int nodeX, int nodeY, int nodeWidth) {
        for (Action action : Action.values()) {
            if (bounds(action, nodeX, nodeY, nodeWidth).contains(point)) {
                return action;
            }
        }
        return null;
    }

    static void paint(
            Graphics2D graphics,
            int nodeX,
            int nodeY,
            int nodeWidth,
            Action hovered,
            boolean focusActive
    ) {
        paintButton(graphics, bounds(Action.FOCUS, nodeX, nodeY, nodeWidth), Action.FOCUS, hovered, focusActive);
        paintButton(graphics, bounds(Action.NAVIGATE, nodeX, nodeY, nodeWidth), Action.NAVIGATE, hovered, false);
    }

    private static void paintButton(
            Graphics2D graphics,
            Rectangle bounds,
            Action action,
            Action hovered,
            boolean active
    ) {
        Graphics2D g = (Graphics2D) graphics.create();
        Color accent = JBColor.namedColor("Focus.color", new JBColor(0x3574F0, 0x548AF7));
        Color background = active
                ? accent
                : hovered == action
                ? JBColor.namedColor("Button.hoverBackground", new JBColor(0xE8EAED, 0x4E5157))
                : JBColor.namedColor("Button.background", new JBColor(0xF7F8FA, 0x3C3F43));
        Color foreground = active
                ? Color.WHITE
                : JBColor.namedColor("Label.foreground", new JBColor(0x3C4043, 0xD7DAE0));
        Color border = active
                ? accent
                : JBColor.namedColor("Component.borderColor", new JBColor(0xA8B3C7, 0x69717D));

        g.setStroke(new BasicStroke(1f));
        g.setColor(background);
        g.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 7, 7);
        g.setColor(border);
        g.drawRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 7, 7);
        g.setColor(foreground);
        g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        if (action == Action.FOCUS) {
            drawFocus(g, bounds);
        } else {
            drawNavigate(g, bounds);
        }
        g.dispose();
    }

    private static void drawFocus(Graphics2D g, Rectangle bounds) {
        int centerX = bounds.x + bounds.width / 2;
        int centerY = bounds.y + bounds.height / 2;
        g.drawOval(centerX - 4, centerY - 4, 8, 8);
        g.fillOval(centerX - 1, centerY - 1, 3, 3);
        g.drawLine(centerX, bounds.y + 2, centerX, bounds.y + 5);
        g.drawLine(centerX, bounds.y + bounds.height - 5, centerX, bounds.y + bounds.height - 2);
        g.drawLine(bounds.x + 2, centerY, bounds.x + 5, centerY);
        g.drawLine(bounds.x + bounds.width - 5, centerY, bounds.x + bounds.width - 2, centerY);
    }

    private static void drawNavigate(Graphics2D g, Rectangle bounds) {
        int left = bounds.x + 4;
        int bottom = bounds.y + bounds.height - 4;
        int right = bounds.x + bounds.width - 4;
        int top = bounds.y + 4;
        g.drawLine(left, bottom, right, top);
        Path2D arrow = new Path2D.Double();
        arrow.moveTo(right - 5, top);
        arrow.lineTo(right, top);
        arrow.lineTo(right, top + 5);
        g.draw(arrow);
        g.drawLine(left, bounds.y + 8, left, bottom);
        g.drawLine(left, bottom, bounds.x + 10, bottom);
    }

    enum Action {
        FOCUS,
        NAVIGATE
    }
}
