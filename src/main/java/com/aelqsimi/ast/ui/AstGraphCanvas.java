package com.aelqsimi.ast.ui;

import com.aelqsimi.ast.AstLensBundle;
import com.aelqsimi.ast.model.AstNode;
import com.intellij.ui.JBColor;
import com.intellij.util.concurrency.AppExecutorUtil;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.Comparator;
import java.util.Set;
import java.util.function.Consumer;

public final class AstGraphCanvas extends JComponent {
    private static final int NODE_WIDTH = AstGraphLayout.NODE_WIDTH;
    private static final int NODE_HEIGHT = AstGraphLayout.NODE_HEIGHT;
    private static final int DRAG_THRESHOLD = 3;
    private static final int DRAG_MARGIN = 12;

    private final Consumer<AstNode> navigator;
    private AstGraphLayout baseGraphLayout = AstGraphLayout.empty();
    private AstGraphLayout graphLayout = AstGraphLayout.empty();
    private double zoom = 1.0;
    private double focusZoom = 1.0;
    private boolean focusConnectedNodes;
    private Set<AstNode> focusedNodes = Set.of();
    private Rectangle focusBounds = new Rectangle();
    private long focusRequestId;
    private AstNode selectedNode;
    private AstNode draggedNode;
    private Point dragOffset = new Point();
    private Point dragOrigin;
    private boolean dragOccurred;
    private NodeControl pressedControl;
    private NodeControl hoveredControl;

    public AstGraphCanvas(Consumer<AstNode> navigator) {
        this.navigator = navigator;
        setOpaque(true);
        setBackground(JBColor.namedColor("Editor.background", new JBColor(0xFFFFFF, 0x1E1F22)));
        setToolTipText("");
        MouseAdapter mouseHandler = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                dragOccurred = false;
                pressedControl = null;
                if (!SwingUtilities.isLeftMouseButton(event)) {
                    return;
                }
                NodeControl control = findNodeControl(event.getPoint());
                if (control != null) {
                    pressedControl = control;
                    draggedNode = null;
                    dragOrigin = null;
                    return;
                }
                AstGraphLayout.Node visual = findVisualNode(event.getPoint());
                if (visual == null) {
                    return;
                }
                focusRequestId++;
                draggedNode = visual.node();
                Point logicalPoint = inverseTransform(event.getPoint());
                dragOffset = new Point(logicalPoint.x - visual.x(), logicalPoint.y - visual.y());
                dragOrigin = event.getPoint();
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                moveDraggedNode(event);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                if (draggedNode == null) {
                    return;
                }
                draggedNode = null;
                dragOrigin = null;
                updateCursor(event.getPoint());
            }

            @Override
            public void mouseClicked(MouseEvent event) {
                if (pressedControl != null) {
                    NodeControl releasedControl = findNodeControl(event.getPoint());
                    NodeControl control = pressedControl;
                    pressedControl = null;
                    if (sameControl(control, releasedControl)) {
                        activateControl(control);
                    }
                    event.consume();
                    return;
                }
                if (dragOccurred) {
                    dragOccurred = false;
                    event.consume();
                    return;
                }
                AstNode clicked = findNode(event.getPoint());
                selectNode(clicked, false);
            }

            @Override
            public void mouseMoved(MouseEvent event) {
                updateHoveredControl(event.getPoint());
                updateCursor(event.getPoint());
            }

            @Override
            public void mouseExited(MouseEvent event) {
                if (draggedNode == null) {
                    setCursor(Cursor.getDefaultCursor());
                }
                if (hoveredControl != null) {
                    hoveredControl = null;
                    repaint();
                }
            }
        };
        addMouseListener(mouseHandler);
        addMouseMotionListener(mouseHandler);
    }

    private static String kindLabel(AstNode.Kind kind) {
        return AstLensBundle.message("node.kind." + kind.name().toLowerCase());
    }

    private static Color colorFor(AstNode.Kind kind) {
        return switch (kind) {
            case FILE -> new JBColor(0xE3F2FD, 0x29415E);
            case CLASS -> new JBColor(0xE8EAF6, 0x3B3F66);
            case METHOD -> new JBColor(0xE8F5E9, 0x31543A);
            case FIELD -> new JBColor(0xFFF3E0, 0x5A4728);
            case CALL -> new JBColor(0xFCE4EC, 0x5D3442);
        };
    }

    private static Color contrastColor(Color background) {
        double luminance = 0.2126 * background.getRed()
                + 0.7152 * background.getGreen()
                + 0.0722 * background.getBlue();
        return luminance < 128 ? Color.WHITE : new Color(0x202124);
    }

    private static boolean sameControl(NodeControl first, NodeControl second) {
        return first != null
                && second != null
                && first.node() == second.node()
                && first.action() == second.action();
    }

    public void setFocusConnectedNodes(boolean enabled) {
        if (focusConnectedNodes == enabled) {
            return;
        }
        focusConnectedNodes = enabled;
        if (enabled && selectedNode != null) {
            requestFocus(selectedNode);
        } else {
            restoreBaseLayout();
        }
    }

    public void zoomIn() {
        setZoom(Math.min(2.0, zoom + 0.1));
    }

    public void zoomOut() {
        setZoom(Math.max(0.4, zoom - 0.1));
    }

    public void resetZoom() {
        setZoom(1.0);
    }

    public void selectNodeAtOffset(int offset) {
        AstNode match = graphLayout.nodes().stream()
                .filter(visual -> visual.node().containsOffset(offset))
                .min(Comparator
                        .comparingInt((AstGraphLayout.Node visual) ->
                                visual.node().endOffset() - visual.node().startOffset())
                        .thenComparingInt(AstGraphLayout.Node::depth))
                .map(AstGraphLayout.Node::node)
                .orElse(null);
        selectNode(match, true);
    }

    private void setZoom(double value) {
        if (Math.abs(value - zoom) < 0.001) {
            return;
        }
        zoom = value;
        if (!focusedNodes.isEmpty()) {
            focusZoom = calculateFocusZoom(focusBounds);
        }
        updateCanvasSize();
        repaint();
        revealFocus();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Rectangle2D visibleArea = visibleArea(graphics.getClipBounds());
        Graphics2D g = (Graphics2D) graphics.create();
        g.scale(activeZoom(), activeZoom());
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g.setStroke(new BasicStroke(1.5f));
        g.setColor(JBColor.namedColor("Component.borderColor", new JBColor(0xA8B3C7, 0x596273)));
        graphLayout.edges().stream()
                .filter(edge -> edge.bounds().intersects(visibleArea))
                .forEach(edge -> drawEdge(g, edge));
        graphLayout.nodes().stream()
                .filter(node -> node.bounds().intersects(visibleArea))
                .forEach(node -> drawNode(g, node));
        g.dispose();
    }

    private Rectangle2D visibleArea(Rectangle clip) {
        Rectangle effectiveClip = clip == null ? new Rectangle(0, 0, getWidth(), getHeight()) : clip;
        return new Rectangle2D.Double(
                effectiveClip.x / activeZoom(),
                effectiveClip.y / activeZoom(),
                effectiveClip.width / activeZoom(),
                effectiveClip.height / activeZoom()
        );
    }

    private void drawEdge(Graphics2D g, AstGraphLayout.Edge edge) {
        Composite originalComposite = g.getComposite();
        boolean focused = isFocusedEdge(edge);
        if (!focusedNodes.isEmpty() && !focused) {
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.14f));
        }
        g.setStroke(new BasicStroke(focused ? 3f : 1.5f));
        g.setColor(focused
                ? JBColor.namedColor("Focus.color", new JBColor(0x3574F0, 0x548AF7))
                : JBColor.namedColor("Component.borderColor", new JBColor(0xA8B3C7, 0x596273)));
        double startX = edge.parent().x() + NODE_WIDTH / 2.0;
        double startY = edge.parent().y() + NODE_HEIGHT;
        double endX = edge.child().x() + NODE_WIDTH / 2.0;
        double endY = edge.child().y();
        double middleY = (startY + endY) / 2.0;

        Path2D path = new Path2D.Double();
        path.moveTo(startX, startY);
        path.lineTo(startX, middleY);
        path.lineTo(endX, middleY);
        path.lineTo(endX, endY);
        g.draw(path);

        Path2D arrow = new Path2D.Double();
        arrow.moveTo(endX, endY);
        arrow.lineTo(endX - 5, endY - 8);
        arrow.lineTo(endX + 5, endY - 8);
        arrow.closePath();
        g.fill(arrow);
        g.setComposite(originalComposite);
    }

    void setLayout(AstGraphLayout layout) {
        baseGraphLayout = layout == null ? AstGraphLayout.empty() : layout;
        graphLayout = baseGraphLayout;
        focusRequestId++;
        focusedNodes = Set.of();
        focusBounds = new Rectangle();
        focusZoom = zoom;
        selectedNode = null;
        draggedNode = null;
        dragOrigin = null;
        dragOccurred = false;
        pressedControl = null;
        hoveredControl = null;
        setCursor(Cursor.getDefaultCursor());
        updateCanvasSize();
        repaint();
    }

    private void drawCenteredEllipsized(Graphics2D g, String text, int x, int y, int width) {
        FontMetrics metrics = g.getFontMetrics();
        String displayed = text;
        while (displayed.length() > 3 && metrics.stringWidth(displayed + "…") > width) {
            displayed = displayed.substring(0, displayed.length() - 1);
        }
        if (!displayed.equals(text)) {
            displayed += "…";
        }
        int textX = x + Math.max(0, (width - metrics.stringWidth(displayed)) / 2);
        g.drawString(displayed, textX, y + metrics.getAscent());
    }

    private void drawNode(Graphics2D g, AstGraphLayout.Node visual) {
        AstNode node = visual.node();
        Composite originalComposite = g.getComposite();
        boolean connected = focusedNodes.contains(node);
        if (!focusedNodes.isEmpty() && !connected) {
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.20f));
        }
        Color fill = colorFor(node.kind());
        RoundRectangle2D box = new RoundRectangle2D.Double(
                visual.x(),
                visual.y(),
                NODE_WIDTH,
                NODE_HEIGHT,
                12,
                12
        );

        g.setColor(fill);
        g.fill(box);
        g.setStroke(new BasicStroke(node == selectedNode ? 3.5f : connected ? 2.5f : 1.5f));
        g.setColor(node == selectedNode || connected
                ? JBColor.namedColor("Focus.color", new JBColor(0x3574F0, 0x548AF7))
                : fill.darker());
        g.draw(box);

        g.setColor(contrastColor(fill));
        Font originalFont = g.getFont();
        g.setFont(originalFont.deriveFont(Font.BOLD));
        drawCenteredEllipsized(
                g,
                node.name(),
                visual.x() + 10,
                visual.y() + 10,
                NODE_WIDTH - 20 - GraphNodeControls.reservedWidth()
        );
        g.setFont(originalFont.deriveFont(Math.max(10f, originalFont.getSize2D() - 1f)));
        drawCenteredEllipsized(g, kindLabel(node.kind()), visual.x() + 10, visual.y() + 31, NODE_WIDTH - 20);
        g.setFont(originalFont);
        GraphNodeControls.Action hovered = hoveredControl != null && hoveredControl.node() == node
                ? hoveredControl.action()
                : null;
        GraphNodeControls.paint(
                g,
                visual.x(),
                visual.y(),
                NODE_WIDTH,
                hovered,
                node == selectedNode && !focusedNodes.isEmpty()
        );
        g.setComposite(originalComposite);
    }

    private AstGraphLayout.Node findVisualNode(Point point) {
        Point transformed = inverseTransform(point);
        for (int i = graphLayout.nodes().size() - 1; i >= 0; i--) {
            AstGraphLayout.Node visual = graphLayout.nodes().get(i);
            if (new RoundRectangle2D.Double(visual.x(), visual.y(), NODE_WIDTH, NODE_HEIGHT, 12, 12)
                    .contains(transformed)) {
                return visual;
            }
        }
        return null;
    }

    private AstNode findNode(Point point) {
        AstGraphLayout.Node visual = findVisualNode(point);
        return visual == null ? null : visual.node();
    }

    private NodeControl findNodeControl(Point point) {
        Point transformed = inverseTransform(point);
        AstGraphLayout.Node visual = findVisualNode(point);
        if (visual == null) {
            return null;
        }
        GraphNodeControls.Action action = GraphNodeControls.actionAt(
                transformed,
                visual.x(),
                visual.y(),
                NODE_WIDTH
        );
        return action == null ? null : new NodeControl(visual.node(), action);
    }

    private void activateControl(NodeControl control) {
        if (control.action() == GraphNodeControls.Action.FOCUS) {
            focusNode(control.node());
            return;
        }
        if (focusConnectedNodes) {
            selectNode(control.node(), false);
        } else {
            selectedNode = control.node();
            repaint();
        }
        navigator.accept(control.node());
    }

    private void focusNode(AstNode node) {
        selectedNode = node;
        repaint();
        requestFocus(node, true);
    }

    private void updateHoveredControl(Point point) {
        NodeControl hovered = findNodeControl(point);
        if (sameControl(hoveredControl, hovered)) {
            return;
        }
        hoveredControl = hovered;
        repaint();
    }

    private void moveDraggedNode(MouseEvent event) {
        if (draggedNode == null || dragOrigin == null) {
            return;
        }
        if (!dragOccurred && dragOrigin.distance(event.getPoint()) < DRAG_THRESHOLD) {
            return;
        }

        AstGraphLayout.Node current = graphLayout.nodes().stream()
                .filter(visual -> visual.node() == draggedNode)
                .findFirst()
                .orElse(null);
        if (current == null) {
            return;
        }

        dragOccurred = true;
        selectedNode = draggedNode;
        Point logicalPoint = inverseTransform(event.getPoint());
        int x = Math.max(DRAG_MARGIN, logicalPoint.x - dragOffset.x);
        int y = Math.max(DRAG_MARGIN, logicalPoint.y - dragOffset.y);
        int deltaX = x - current.x();
        int deltaY = y - current.y();
        if (deltaX == 0 && deltaY == 0) {
            return;
        }

        graphLayout = graphLayout.moveNode(draggedNode, x, y);
        if (focusedNodes.isEmpty()) {
            baseGraphLayout = graphLayout;
        } else {
            AstGraphLayout.Node baseNode = baseGraphLayout.nodes().stream()
                    .filter(visual -> visual.node() == draggedNode)
                    .findFirst()
                    .orElse(null);
            if (baseNode != null) {
                baseGraphLayout = baseGraphLayout.moveNode(
                        draggedNode,
                        Math.max(DRAG_MARGIN, baseNode.x() + deltaX),
                        Math.max(DRAG_MARGIN, baseNode.y() + deltaY)
                );
            }
            refreshFocusBounds();
        }
        setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
        updateCanvasSize();
        repaint();
    }

    private void refreshFocusBounds() {
        Rectangle bounds = null;
        for (AstGraphLayout.Node visual : graphLayout.nodes()) {
            if (!focusedNodes.contains(visual.node())) {
                continue;
            }
            bounds = bounds == null ? visual.bounds() : bounds.union(visual.bounds());
        }
        if (bounds == null) {
            focusBounds = new Rectangle();
            return;
        }
        bounds.grow(45, 45);
        focusBounds = bounds;
    }

    private void updateCursor(Point point) {
        if (findNodeControl(point) != null) {
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        } else if (findVisualNode(point) != null) {
            setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
        } else {
            setCursor(Cursor.getDefaultCursor());
        }
    }

    private void selectNode(AstNode node, boolean scrollToNode) {
        if (selectedNode == node) {
            if (focusConnectedNodes && node != null && focusedNodes.isEmpty()) {
                requestFocus(node);
            }
            if (scrollToNode) {
                revealSelection(node);
            }
            return;
        }
        selectedNode = node;
        repaint();
        if (focusConnectedNodes && node != null) {
            requestFocus(node);
            return;
        }
        restoreBaseLayout();
        if (!scrollToNode || node == null) {
            return;
        }
        revealSelection(node);
    }

    private void revealSelection(AstNode node) {
        double scale = activeZoom();
        graphLayout.nodes().stream()
                .filter(visual -> visual.node() == node)
                .findFirst()
                .ifPresent(visual -> scrollRectToVisible(new Rectangle(
                        (int) (visual.x() * scale) - 20,
                        (int) (visual.y() * scale) - 20,
                        (int) (NODE_WIDTH * scale) + 40,
                        (int) (NODE_HEIGHT * scale) + 40
                )));
    }

    private void requestFocus(AstNode node) {
        requestFocus(node, false);
    }

    private void requestFocus(AstNode node, boolean explicit) {
        long requestId = ++focusRequestId;
        AstGraphLayout sourceLayout = baseGraphLayout;
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            AstGraphLayout.Focus focus = sourceLayout.focus(node);
            SwingUtilities.invokeLater(() -> {
                if (requestId != focusRequestId
                        || (!explicit && !focusConnectedNodes)
                        || selectedNode != node) {
                    return;
                }
                graphLayout = focus.layout();
                focusedNodes = focus.nodes();
                focusBounds = focus.bounds();
                focusZoom = calculateFocusZoom(focusBounds);
                updateCanvasSize();
                repaint();
                revealFocus();
            });
        });
    }

    private void restoreBaseLayout() {
        focusRequestId++;
        graphLayout = baseGraphLayout;
        focusedNodes = Set.of();
        focusBounds = new Rectangle();
        focusZoom = zoom;
        updateCanvasSize();
        repaint();
    }

    private boolean isFocusedEdge(AstGraphLayout.Edge edge) {
        return selectedNode != null
                && (edge.parent().node() == selectedNode || edge.child().node() == selectedNode);
    }

    private double activeZoom() {
        return focusedNodes.isEmpty() ? zoom : focusZoom;
    }

    private double calculateFocusZoom(Rectangle bounds) {
        if (bounds.isEmpty()) {
            return zoom;
        }
        Dimension extent = getParent() instanceof JViewport viewport
                ? viewport.getExtentSize()
                : getVisibleRect().getSize();
        if (extent.width <= 0 || extent.height <= 0) {
            return zoom;
        }
        double widthScale = Math.max(1, extent.width - 32) / (double) bounds.width;
        double heightScale = Math.max(1, extent.height - 32) / (double) bounds.height;
        return Math.max(0.20, Math.min(zoom, Math.min(widthScale, heightScale)));
    }

    private void updateCanvasSize() {
        Dimension logicalSize = graphLayout.logicalSize();
        double scale = activeZoom();
        setPreferredSize(new Dimension(
                (int) Math.ceil(logicalSize.width * scale),
                (int) Math.ceil(logicalSize.height * scale)
        ));
        revalidate();
    }

    private void revealFocus() {
        if (focusedNodes.isEmpty() || focusBounds.isEmpty()) {
            return;
        }
        double scale = activeZoom();
        SwingUtilities.invokeLater(() -> scrollRectToVisible(new Rectangle(
                (int) Math.floor(focusBounds.x * scale),
                (int) Math.floor(focusBounds.y * scale),
                (int) Math.ceil(focusBounds.width * scale),
                (int) Math.ceil(focusBounds.height * scale)
        )));
    }

    private Point inverseTransform(Point point) {
        try {
            return new Point((int) (point.x / activeZoom()), (int) (point.y / activeZoom()));
        } catch (RuntimeException ignored) {
            return point;
        }
    }

    @Override
    public String getToolTipText(MouseEvent event) {
        NodeControl control = findNodeControl(event.getPoint());
        if (control != null) {
            return AstLensBundle.message(control.action() == GraphNodeControls.Action.FOCUS
                    ? "graph.node.action.focus"
                    : "graph.node.action.navigate");
        }
        AstNode node = findNode(event.getPoint());
        return node == null ? null : AstLensBundle.message("graph.tooltip", kindLabel(node.kind()), node.name());
    }

    private record NodeControl(AstNode node, GraphNodeControls.Action action) {
    }

}
