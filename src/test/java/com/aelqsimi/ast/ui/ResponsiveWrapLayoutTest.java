package com.aelqsimi.ast.ui;

import org.junit.Test;

import javax.swing.*;
import java.awt.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ResponsiveWrapLayoutTest {
    @Test
    public void wrapsControlsInsteadOfClippingThemWhenWidthShrinks() {
        JPanel toolbar = new JPanel(new ResponsiveWrapLayout(6, 4));
        for (int index = 0; index < 6; index++) {
            JButton button = new JButton("Action " + index);
            button.setPreferredSize(new Dimension(120, 28));
            toolbar.add(button);
        }

        toolbar.setSize(760, 200);
        Dimension wide = toolbar.getPreferredSize();
        toolbar.setSize(260, 500);
        Dimension narrow = toolbar.getPreferredSize();
        toolbar.setSize(260, narrow.height);
        toolbar.doLayout();

        assertTrue(narrow.height > wide.height);
        for (Component component : toolbar.getComponents()) {
            assertTrue(component.getX() >= 0);
            assertTrue(component.getY() >= 0);
            assertTrue(component.getX() + component.getWidth() <= toolbar.getWidth());
            assertTrue(component.getY() + component.getHeight() <= toolbar.getHeight());
        }
    }

    @Test
    public void shrinksASingleWideControlToTheAvailableWidth() {
        JPanel toolbar = new JPanel(new ResponsiveWrapLayout(6, 4));
        JComboBox<String> selector = new JComboBox<>(new String[]{"A very long relationship description"});
        selector.setPreferredSize(new Dimension(420, 30));
        toolbar.add(selector);
        toolbar.setSize(220, 100);
        toolbar.doLayout();

        assertEquals(208, selector.getWidth());
        assertTrue(selector.getX() + selector.getWidth() <= toolbar.getWidth());
    }
}
