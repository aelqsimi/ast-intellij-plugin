package com.aelqsimi.ast.ui;

import com.aelqsimi.ast.AstLensBundle;
import com.aelqsimi.ast.model.CallGraphNode;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.util.concurrency.AppExecutorUtil;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.*;
import java.util.Comparator;
import java.util.Set;
import java.util.function.Consumer;

public final class CallGraphCanvas extends JComponent {
    private static final int NODE_WIDTH = CallGraphLayout.NODE_WIDTH;
    private static final int NODE_HEIGHT = CallGraphLayout.NODE_HEIGHT;

    private final Consumer<CallGraphNode> navigator;
    private final String tooltipKey;
    private final String kindKeyPrefix;
    private CallGraphLayout baseGraphLayout = CallGraphLayout.empty();
    private CallGraphLayout graphLayout = CallGraphLayout.empty();
    private double zoom = 1.0;
    private double focusZoom = 1.0;
    private boolean focusConnectedNodes;
    private Set<String> focusedNodeIds = Set.of();
    private Rectangle focusBounds = new Rectangle();
    private long focusRequestId;
    private CallGraphNode selectedNode;

    public CallGraphCanvas(Consumer<CallGraphNode> navigator) {
        this(navigator, "call.graph.tooltip", "call.node.kind.");
    }

    public CallGraphCanvas(Consumer<CallGraphNode> navigator, String tooltipKey, String kindKeyPrefix) {
        this.navigator = navigator;
        this.tooltipKey = tooltipKey;
        this.kindKeyPrefix = kindKeyPrefix;
        setOpaque(true);
        setBackground(JBColor.namedColor("Editor.background", new JBColor(0xFFFFFF, 0x1E1F22)));
        setToolTipText("");
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                CallGraphNode clicked = findNode(event.getPoint());
                selectNode(clicked, false);
                if (clicked != null && event.getClickCount() == 2) {
                    navigator.accept(clicked);
                }
            }
        });
    }

    private static PointD boundaryPoint(PointD from, PointD toward) {
        double dx = toward.x - from.x;
        double dy = toward.y - from.y;
        double scaleX = dx == 0 ? Double.POSITIVE_INFINITY : (NODE_WIDTH / 2.0) / Math.abs(dx);
        double scaleY = dy == 0 ? Double.POSITIVE_INFINITY : (NODE_HEIGHT / 2.0) / Math.abs(dy);
        double scale = Math.min(scaleX, scaleY);
        return new PointD(from.x + dx * scale, from.y + dy * scale);
    }

    private static Color colorFor(CallGraphNode.Kind kind) {
        return switch (kind) {
            case INTERNAL -> new JBColor(0xE8F5E9, 0x31543A);
            case EXTERNAL -> new JBColor(0xE3F2FD, 0x29415E);
            case UNRESOLVED -> new JBColor(0xFCE4EC, 0x5D3442);
        };
    }

    private static Color contrastColor(Color background) {
        double luminance = 0.2126 * background.getRed()
                + 0.7152 * background.getGreen()
                + 0.0722 * background.getBlue();
        return luminance < 128 ? Color.WHITE : new Color(0x202124);
    }

    private static PointD center(CallGraphLayout.Node node) {
        return new PointD(node.x() + NODE_WIDTH / 2.0, node.y() + NODE_HEIGHT / 2.0);
    }

    private static RoundRectangle2D box(CallGraphLayout.Node node) {
        return new RoundRectangle2D.Double(node.x(), node.y(), NODE_WIDTH, NODE_HEIGHT, 12, 12);
    }

    private static String edgeLabel(CallGraphLayout.Edge edge) {
        if (edge.label().isBlank()) {
            return edge.callCount() > 1 ? "×" + edge.callCount() : "";
        }
        return edge.callCount() > 1
                ? edge.label() + " ×" + edge.callCount()
                : edge.label();
    }

    void setLayout(CallGraphLayout layout) {
        baseGraphLayout = layout == null ? CallGraphLayout.empty() : layout;
        graphLayout = baseGraphLayout;
        focusRequestId++;
        focusedNodeIds = Set.of();
        focusBounds = new Rectangle();
        focusZoom = zoom;
        selectedNode = null;
        updateCanvasSize();
        repaint();
    }

    public void selectNodeAtOffset(VirtualFile file, int offset) {
        CallGraphNode match = graphLayout.nodes().stream()
                .map(CallGraphLayout.Node::node)
                .filter(node -> node.contains(file, offset))
                .min(Comparator.comparingInt(node -> node.endOffset() - node.startOffset()))
                .orElse(null);
        selectNode(match, true);
    }

    public void selectNodeById(String id) {
        CallGraphNode match = id == null
                ? null
                : graphLayout.nodes().stream()
                .map(CallGraphLayout.Node::node)
                .filter(node -> id.equals(node.id()))
                .findFirst()
                .orElse(null);
        selectNode(match, true);
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

    private void setZoom(double value) {
        if (Math.abs(value - zoom) < 0.001) {
            return;
        }
        zoom = value;
        if (!focusedNodeIds.isEmpty()) {
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

    private void drawEdge(Graphics2D g, CallGraphLayout.Edge edge) {
        Composite originalComposite = g.getComposite();
        boolean focused = isFocusedEdge(edge);
        if (!focusedNodeIds.isEmpty() && !focused) {
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.14f));
        }
        g.setStroke(new BasicStroke(focused ? 3f : 1.5f));
        g.setColor(focused
                ? JBColor.namedColor("Focus.color", new JBColor(0x3574F0, 0x548AF7))
                : JBColor.namedColor("Component.borderColor", new JBColor(0x8794A8, 0x667085)));
        if (edge.source() == edge.target()) {
            drawSelfEdge(g, edge);
            g.setComposite(originalComposite);
            return;
        }

        PointD sourceCenter = center(edge.source());
        PointD targetCenter = center(edge.target());
        PointD start = boundaryPoint(sourceCenter, targetCenter);
        PointD end = boundaryPoint(targetCenter, sourceCenter);
        double middleX = (start.x + end.x) / 2.0;
        double middleY = (start.y + end.y) / 2.0;
        double dx = end.x - start.x;
        double dy = end.y - start.y;
        double length = Math.max(1.0, Math.hypot(dx, dy));
        double direction = edge.source().node().id().compareTo(edge.target().node().id()) <= 0 ? 1.0 : -1.0;
        double curve = Math.min(42.0, length * 0.12) * direction;
        double controlX = middleX - dy / length * curve;
        double controlY = middleY + dx / length * curve;

        g.draw(new QuadCurve2D.Double(start.x, start.y, controlX, controlY, end.x, end.y));
        drawArrow(g, new PointD(controlX, controlY), end);
        String label = edgeLabel(edge);
        if (!label.isEmpty()) {
            drawEdgeLabel(g, label, controlX, controlY);
        }
        g.setComposite(originalComposite);
    }

    private void drawArrow(Graphics2D g, PointD from, PointD end) {
        double angle = Math.atan2(end.y - from.y, end.x - from.x);
        Path2D arrow = new Path2D.Double();
        arrow.moveTo(end.x, end.y);
        arrow.lineTo(end.x - 10 * Math.cos(angle - Math.PI / 6), end.y - 10 * Math.sin(angle - Math.PI / 6));
        arrow.lineTo(end.x - 10 * Math.cos(angle + Math.PI / 6), end.y - 10 * Math.sin(angle + Math.PI / 6));
        arrow.closePath();
        g.fill(arrow);
    }

    private void drawSelfEdge(Graphics2D g, CallGraphLayout.Edge edge) {
        double x = edge.source().x() + NODE_WIDTH * 0.3;
        double y = edge.source().y() - 42;
        double width = NODE_WIDTH * 0.4;
        g.draw(new Arc2D.Double(x, y, width, 58, 15, 300, Arc2D.OPEN));
        PointD end = new PointD(x + width, y + 36);
        drawArrow(g, new PointD(x + width + 5, y + 20), end);
        String label = edgeLabel(edge);
        if (!label.isEmpty()) {
            drawEdgeLabel(g, label, x + width / 2.0, y);
        }
    }

    private void drawEdgeLabel(Graphics2D g, String label, double x, double y) {
        FontMetrics metrics = g.getFontMetrics();
        int width = metrics.stringWidth(label) + 8;
        g.setColor(getBackground());
        g.fillRoundRect((int) x - width / 2, (int) y - 10, width, 18, 8, 8);
        g.setColor(JBColor.foreground());
        g.drawString(label, (int) x - metrics.stringWidth(label) / 2, (int) y + 4);
    }

    private void drawNode(Graphics2D g, CallGraphLayout.Node visual) {
        CallGraphNode node = visual.node();
        Composite originalComposite = g.getComposite();
        boolean connected = focusedNodeIds.contains(node.id());
        if (!focusedNodeIds.isEmpty() && !connected) {
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.20f));
        }
        Color fill = colorFor(node.kind());
        RoundRectangle2D box = box(visual);
        g.setColor(fill);
        g.fill(box);
        g.setStroke(new BasicStroke(node == selectedNode ? 3.5f : connected ? 2.5f : 1.5f));
        g.setColor(node == selectedNode || connected
                ? JBColor.namedColor("Focus.color", new JBColor(0x3574F0, 0x548AF7))
                : fill.darker());
        g.draw(box);

        g.setColor(contrastColor(fill));
        Font original = g.getFont();
        g.setFont(original.deriveFont(Font.BOLD));
        drawCenteredEllipsized(g, node.label(), visual.x() + 10, visual.y() + 10, NODE_WIDTH - 20);
        g.setFont(original.deriveFont(Math.max(10f, original.getSize2D() - 1f)));
        drawCenteredEllipsized(g, kindLabel(node.kind()), visual.x() + 10, visual.y() + 32, NODE_WIDTH - 20);
        g.setFont(original);
        g.setComposite(originalComposite);
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

    private CallGraphNode findNode(Point point) {
        Point transformed = new Point((int) (point.x / activeZoom()), (int) (point.y / activeZoom()));
        for (int index = graphLayout.nodes().size() - 1; index >= 0; index--) {
            CallGraphLayout.Node visual = graphLayout.nodes().get(index);
            if (box(visual).contains(transformed)) {
                return visual.node();
            }
        }
        return null;
    }

    private void selectNode(CallGraphNode node, boolean scrollToNode) {
        if (selectedNode == node) {
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

    private void revealSelection(CallGraphNode node) {
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

    private void requestFocus(CallGraphNode node) {
        long requestId = ++focusRequestId;
        CallGraphLayout sourceLayout = baseGraphLayout;
        String selectedId = node.id();
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            CallGraphLayout.Focus focus = sourceLayout.focus(selectedId);
            SwingUtilities.invokeLater(() -> {
                if (requestId != focusRequestId
                        || !focusConnectedNodes
                        || selectedNode == null
                        || !selectedId.equals(selectedNode.id())) {
                    return;
                }
                graphLayout = focus.layout();
                focusedNodeIds = focus.nodeIds();
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
        focusedNodeIds = Set.of();
        focusBounds = new Rectangle();
        focusZoom = zoom;
        updateCanvasSize();
        repaint();
    }

    private boolean isFocusedEdge(CallGraphLayout.Edge edge) {
        return selectedNode != null
                && (edge.source().node().id().equals(selectedNode.id())
                || edge.target().node().id().equals(selectedNode.id()));
    }

    private double activeZoom() {
        return focusedNodeIds.isEmpty() ? zoom : focusZoom;
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
        if (focusedNodeIds.isEmpty() || focusBounds.isEmpty()) {
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

    @Override
    public String getToolTipText(MouseEvent event) {
        CallGraphNode node = findNode(event.getPoint());
        return node == null
                ? null
                : AstLensBundle.message(tooltipKey, kindLabel(node.kind()), node.label());
    }

    private String kindLabel(CallGraphNode.Kind kind) {
        return AstLensBundle.message(kindKeyPrefix + kind.name().toLowerCase());
    }

    private record PointD(double x, double y) {
    }
}
