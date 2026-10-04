package com.aelqsimi.ast.ui;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * A compact left-to-right layout that grows vertically when the available width changes.
 * Unlike {@link FlowLayout}, its preferred height includes wrapped rows.
 */
final class ResponsiveWrapLayout implements LayoutManager {
    private static final int FALLBACK_WIDTH = 900;

    private final int horizontalGap;
    private final int verticalGap;
    private int lastWidth = -1;
    private int lastPreferredHeight = -1;

    ResponsiveWrapLayout(int horizontalGap, int verticalGap) {
        this.horizontalGap = horizontalGap;
        this.verticalGap = verticalGap;
    }

    @Override
    public void addLayoutComponent(String name, Component component) {
    }

    @Override
    public void removeLayoutComponent(Component component) {
    }

    @Override
    public Dimension preferredLayoutSize(Container target) {
        synchronized (target.getTreeLock()) {
            return calculateSize(target, false);
        }
    }

    @Override
    public Dimension minimumLayoutSize(Container target) {
        synchronized (target.getTreeLock()) {
            return calculateSize(target, true);
        }
    }

    @Override
    public void layoutContainer(Container target) {
        synchronized (target.getTreeLock()) {
            Insets insets = target.getInsets();
            int availableWidth = availableWidth(target, insets);
            List<Row> rows = rows(target, availableWidth, false);
            int y = insets.top + verticalGap;
            for (Row row : rows) {
                int x = insets.left + horizontalGap;
                for (Item item : row.items()) {
                    int componentY = y + (row.height() - item.height()) / 2;
                    item.component().setBounds(x, componentY, item.width(), item.height());
                    x += item.width() + horizontalGap;
                }
                y += row.height() + verticalGap;
            }

            int preferredHeight = calculateHeight(insets, rows);
            if (lastWidth != target.getWidth() || lastPreferredHeight != preferredHeight) {
                lastWidth = target.getWidth();
                lastPreferredHeight = preferredHeight;
                Container parent = target.getParent();
                if (parent != null) {
                    SwingUtilities.invokeLater(parent::revalidate);
                }
            }
        }
    }

    private Dimension calculateSize(Container target, boolean minimum) {
        Insets insets = target.getInsets();
        int availableWidth = availableWidth(target, insets);
        List<Row> rows = rows(target, availableWidth, minimum);
        int contentWidth = rows.stream().mapToInt(Row::width).max().orElse(0);
        return new Dimension(
                contentWidth + insets.left + insets.right + horizontalGap * 2,
                calculateHeight(insets, rows)
        );
    }

    private int availableWidth(Container target, Insets insets) {
        int width = target.getWidth();
        if (width <= 0 && target.getParent() != null) {
            width = target.getParent().getWidth();
        }
        if (width <= 0) {
            width = FALLBACK_WIDTH;
        }
        return Math.max(1, width - insets.left - insets.right - horizontalGap * 2);
    }

    private List<Row> rows(Container target, int availableWidth, boolean minimum) {
        List<Row> rows = new ArrayList<>();
        List<Item> currentItems = new ArrayList<>();
        int currentWidth = 0;
        int currentHeight = 0;
        for (Component component : target.getComponents()) {
            if (!component.isVisible()) {
                continue;
            }
            Dimension size = minimum ? component.getMinimumSize() : component.getPreferredSize();
            int componentWidth = Math.min(size.width, availableWidth);
            int requiredWidth = currentItems.isEmpty()
                    ? componentWidth
                    : currentWidth + horizontalGap + componentWidth;
            if (!currentItems.isEmpty() && requiredWidth > availableWidth) {
                rows.add(new Row(List.copyOf(currentItems), currentWidth, currentHeight));
                currentItems.clear();
                currentWidth = 0;
                currentHeight = 0;
            }
            currentItems.add(new Item(component, componentWidth, size.height));
            currentWidth = currentWidth == 0
                    ? componentWidth
                    : currentWidth + horizontalGap + componentWidth;
            currentHeight = Math.max(currentHeight, size.height);
        }
        if (!currentItems.isEmpty()) {
            rows.add(new Row(List.copyOf(currentItems), currentWidth, currentHeight));
        }
        return rows;
    }

    private int calculateHeight(Insets insets, List<Row> rows) {
        int rowsHeight = rows.stream().mapToInt(Row::height).sum();
        int gapsHeight = verticalGap * (rows.size() + 1);
        return insets.top + insets.bottom + rowsHeight + gapsHeight;
    }

    private record Item(Component component, int width, int height) {
    }

    private record Row(List<Item> items, int width, int height) {
    }
}
