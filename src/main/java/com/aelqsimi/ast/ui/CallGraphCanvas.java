package com.aelqsimi.ast.ui;

import com.aelqsimi.ast.AstLensBundle;
import com.aelqsimi.ast.model.CallGraph;
import com.aelqsimi.ast.model.CallGraphEdge;
import com.aelqsimi.ast.model.CallGraphNode;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;
import java.awt.geom.QuadCurve2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class CallGraphCanvas extends JComponent {
    private static final int NODE_WIDTH = 210;
    private static final int NODE_HEIGHT = 58;
    private static final int MARGIN = 70;

    private final Consumer<CallGraphNode> navigator;
    private final String tooltipKey;
    private final String kindKeyPrefix;
    private final List<VisualNode> visualNodes = new ArrayList<>();
    private final List<VisualEdge> visualEdges = new ArrayList<>();
    private double zoom = 1.0;
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

    public void setGraph(CallGraph graph) {
        visualNodes.clear();
        visualEdges.clear();
        selectedNode = null;
        if (graph == null || graph.nodes().isEmpty()) {
            setPreferredSize(new Dimension(500, 300));
            revalidate();
            repaint();
            return;
        }

        int nodeCount = graph.nodes().size();
        double radius = nodeCount == 1 ? 0 : Math.max(190, nodeCount * 48.0);
        int logicalWidth = (int) Math.ceil(radius * 2 + NODE_WIDTH + MARGIN * 2);
        int logicalHeight = (int) Math.ceil(radius * 2 + NODE_HEIGHT + MARGIN * 2);
        double centerX = logicalWidth / 2.0;
        double centerY = logicalHeight / 2.0;

        Map<String, VisualNode> nodesById = new HashMap<>();
        for (int index = 0; index < nodeCount; index++) {
            double angle = nodeCount == 1 ? 0 : -Math.PI / 2 + 2 * Math.PI * index / nodeCount;
            int x = (int) Math.round(centerX + Math.cos(angle) * radius - NODE_WIDTH / 2.0);
            int y = (int) Math.round(centerY + Math.sin(angle) * radius - NODE_HEIGHT / 2.0);
            VisualNode visual = new VisualNode(graph.nodes().get(index), x, y);
            visualNodes.add(visual);
            nodesById.put(visual.node.id(), visual);
        }

        for (CallGraphEdge edge : graph.edges()) {
            VisualNode source = nodesById.get(edge.sourceId());
            VisualNode target = nodesById.get(edge.targetId());
            if (source != null && target != null) {
                visualEdges.add(new VisualEdge(source, target, edge.callCount()));
            }
        }

        setPreferredSize(new Dimension((int) (logicalWidth * zoom), (int) (logicalHeight * zoom)));
        revalidate();
        repaint();
    }

    public void selectNodeAtOffset(VirtualFile file, int offset) {
        CallGraphNode match = visualNodes.stream()
                .map(VisualNode::node)
                .filter(node -> node.contains(file, offset))
                .min(Comparator.comparingInt(node -> node.endOffset() - node.startOffset()))
                .orElse(null);
        selectNode(match, true);
    }

    public void selectNodeById(String id) {
        CallGraphNode match = id == null
                ? null
                : visualNodes.stream()
                        .map(VisualNode::node)
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

    private void setZoom(double value) {
        if (Math.abs(value - zoom) < 0.001) {
            return;
        }
        double ratio = value / zoom;
        zoom = value;
        Dimension size = getPreferredSize();
        setPreferredSize(new Dimension((int) (size.width * ratio), (int) (size.height * ratio)));
        revalidate();
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        g.scale(zoom, zoom);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        visualEdges.forEach(edge -> drawEdge(g, edge));
        visualNodes.forEach(node -> drawNode(g, node));
        g.dispose();
    }

    private void drawEdge(Graphics2D g, VisualEdge edge) {
        g.setStroke(new BasicStroke(1.5f));
        g.setColor(JBColor.namedColor("Component.borderColor", new JBColor(0x8794A8, 0x667085)));
        if (edge.source == edge.target) {
            drawSelfEdge(g, edge);
            return;
        }

        PointD sourceCenter = edge.source.center();
        PointD targetCenter = edge.target.center();
        PointD start = boundaryPoint(sourceCenter, targetCenter);
        PointD end = boundaryPoint(targetCenter, sourceCenter);
        double middleX = (start.x + end.x) / 2.0;
        double middleY = (start.y + end.y) / 2.0;
        double dx = end.x - start.x;
        double dy = end.y - start.y;
        double length = Math.max(1.0, Math.hypot(dx, dy));
        double direction = edge.source.node.id().compareTo(edge.target.node.id()) <= 0 ? 1.0 : -1.0;
        double curve = Math.min(42.0, length * 0.12) * direction;
        double controlX = middleX - dy / length * curve;
        double controlY = middleY + dx / length * curve;

        g.draw(new QuadCurve2D.Double(start.x, start.y, controlX, controlY, end.x, end.y));
        drawArrow(g, new PointD(controlX, controlY), end);
        if (edge.callCount > 1) {
            drawEdgeCount(g, edge.callCount, controlX, controlY);
        }
    }

    private void drawSelfEdge(Graphics2D g, VisualEdge edge) {
        double x = edge.source.x + NODE_WIDTH * 0.3;
        double y = edge.source.y - 42;
        double width = NODE_WIDTH * 0.4;
        g.draw(new Arc2D.Double(x, y, width, 58, 15, 300, Arc2D.OPEN));
        PointD end = new PointD(x + width, y + 36);
        drawArrow(g, new PointD(x + width + 5, y + 20), end);
        if (edge.callCount > 1) {
            drawEdgeCount(g, edge.callCount, x + width / 2.0, y);
        }
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

    private void drawEdgeCount(Graphics2D g, int count, double x, double y) {
        String label = "×" + count;
        FontMetrics metrics = g.getFontMetrics();
        int width = metrics.stringWidth(label) + 8;
        g.setColor(getBackground());
        g.fillRoundRect((int) x - width / 2, (int) y - 10, width, 18, 8, 8);
        g.setColor(JBColor.foreground());
        g.drawString(label, (int) x - metrics.stringWidth(label) / 2, (int) y + 4);
    }

    private void drawNode(Graphics2D g, VisualNode visual) {
        CallGraphNode node = visual.node;
        Color fill = colorFor(node.kind());
        RoundRectangle2D box = visual.box();
        g.setColor(fill);
        g.fill(box);
        g.setStroke(new BasicStroke(node == selectedNode ? 3f : 1.5f));
        g.setColor(node == selectedNode
                ? JBColor.namedColor("Focus.color", new JBColor(0x3574F0, 0x548AF7))
                : fill.darker());
        g.draw(box);

        g.setColor(contrastColor(fill));
        Font original = g.getFont();
        g.setFont(original.deriveFont(Font.BOLD));
        drawCenteredEllipsized(g, node.label(), visual.x + 10, visual.y + 10, NODE_WIDTH - 20);
        g.setFont(original.deriveFont(Math.max(10f, original.getSize2D() - 1f)));
        drawCenteredEllipsized(g, kindLabel(node.kind()), visual.x + 10, visual.y + 32, NODE_WIDTH - 20);
        g.setFont(original);
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
        Point transformed = new Point((int) (point.x / zoom), (int) (point.y / zoom));
        for (int index = visualNodes.size() - 1; index >= 0; index--) {
            VisualNode visual = visualNodes.get(index);
            if (visual.box().contains(transformed)) {
                return visual.node;
            }
        }
        return null;
    }

    private void selectNode(CallGraphNode node, boolean scrollToNode) {
        if (selectedNode == node) {
            return;
        }
        selectedNode = node;
        repaint();
        if (!scrollToNode || node == null) {
            return;
        }
        visualNodes.stream()
                .filter(visual -> visual.node == node)
                .findFirst()
                .ifPresent(visual -> scrollRectToVisible(new Rectangle(
                        (int) (visual.x * zoom) - 20,
                        (int) (visual.y * zoom) - 20,
                        (int) (NODE_WIDTH * zoom) + 40,
                        (int) (NODE_HEIGHT * zoom) + 40
                )));
    }

    @Override
    public String getToolTipText(MouseEvent event) {
        CallGraphNode node = findNode(event.getPoint());
        return node == null
                ? null
                : AstLensBundle.message(tooltipKey, kindLabel(node.kind()), node.label());
    }

    private static PointD boundaryPoint(PointD from, PointD toward) {
        double dx = toward.x - from.x;
        double dy = toward.y - from.y;
        double scaleX = dx == 0 ? Double.POSITIVE_INFINITY : (NODE_WIDTH / 2.0) / Math.abs(dx);
        double scaleY = dy == 0 ? Double.POSITIVE_INFINITY : (NODE_HEIGHT / 2.0) / Math.abs(dy);
        double scale = Math.min(scaleX, scaleY);
        return new PointD(from.x + dx * scale, from.y + dy * scale);
    }

    private String kindLabel(CallGraphNode.Kind kind) {
        return AstLensBundle.message(kindKeyPrefix + kind.name().toLowerCase());
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

    private record VisualNode(CallGraphNode node, int x, int y) {
        private PointD center() {
            return new PointD(x + NODE_WIDTH / 2.0, y + NODE_HEIGHT / 2.0);
        }

        private RoundRectangle2D box() {
            return new RoundRectangle2D.Double(x, y, NODE_WIDTH, NODE_HEIGHT, 12, 12);
        }
    }

    private record VisualEdge(VisualNode source, VisualNode target, int callCount) {
    }

    private record PointD(double x, double y) {
    }
}
