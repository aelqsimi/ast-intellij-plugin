package com.aelqsimi.ast.ui;

import com.aelqsimi.ast.AstLensBundle;
import com.aelqsimi.ast.model.AstNode;
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
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class AstGraphCanvas extends JComponent {
    private static final int NODE_WIDTH = 190;
    private static final int NODE_HEIGHT = 56;
    private static final int HORIZONTAL_GAP = 30;
    private static final int VERTICAL_GAP = 80;
    private static final int MARGIN = 35;

    private final Consumer<AstNode> navigator;
    private final List<VisualNode> visualNodes = new ArrayList<>();
    private final List<Edge> edges = new ArrayList<>();
    private final Map<AstNode, Integer> subtreeWidths = new IdentityHashMap<>();
    private double zoom = 1.0;
    private AstNode selectedNode;

    public AstGraphCanvas(Consumer<AstNode> navigator) {
        this.navigator = navigator;
        setOpaque(true);
        setBackground(JBColor.namedColor("Editor.background", new JBColor(0xFFFFFF, 0x1E1F22)));
        setToolTipText("");
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                AstNode clicked = findNode(event.getPoint());
                selectNode(clicked, false);
                if (clicked != null && event.getClickCount() == 2) {
                    navigator.accept(clicked);
                }
            }
        });
    }

    public void setGraph(AstNode root) {
        visualNodes.clear();
        edges.clear();
        subtreeWidths.clear();
        selectedNode = null;
        if (root == null) {
            setPreferredSize(new Dimension(500, 300));
            revalidate();
            repaint();
            return;
        }

        measure(root);
        layout(root, MARGIN, 0, null);
        int width = subtreeWidths.get(root) + MARGIN * 2;
        int maxDepth = visualNodes.stream().mapToInt(VisualNode::depth).max().orElse(0);
        int height = MARGIN * 2 + NODE_HEIGHT + maxDepth * (NODE_HEIGHT + VERTICAL_GAP);
        setPreferredSize(new Dimension((int) (width * zoom), (int) (height * zoom)));
        revalidate();
        repaint();
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
        AstNode match = visualNodes.stream()
                .filter(visual -> visual.node.containsOffset(offset))
                .min(Comparator
                        .comparingInt((VisualNode visual) -> visual.node.endOffset() - visual.node.startOffset())
                        .thenComparingInt(VisualNode::depth))
                .map(VisualNode::node)
                .orElse(null);
        selectNode(match, true);
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

    private int measure(AstNode node) {
        int childrenWidth = node.children().stream().mapToInt(this::measure).sum();
        if (node.children().size() > 1) {
            childrenWidth += (node.children().size() - 1) * HORIZONTAL_GAP;
        }
        int width = Math.max(NODE_WIDTH, childrenWidth);
        subtreeWidths.put(node, width);
        return width;
    }

    private void layout(AstNode node, int left, int depth, VisualNode parent) {
        int subtreeWidth = subtreeWidths.get(node);
        int x = left + (subtreeWidth - NODE_WIDTH) / 2;
        int y = MARGIN + depth * (NODE_HEIGHT + VERTICAL_GAP);
        VisualNode visual = new VisualNode(node, x, y, depth);
        visualNodes.add(visual);
        if (parent != null) {
            edges.add(new Edge(parent, visual));
        }

        int childrenWidth = node.children().stream().mapToInt(subtreeWidths::get).sum();
        if (node.children().size() > 1) {
            childrenWidth += (node.children().size() - 1) * HORIZONTAL_GAP;
        }
        int childLeft = left + (subtreeWidth - childrenWidth) / 2;
        for (AstNode child : node.children()) {
            layout(child, childLeft, depth + 1, visual);
            childLeft += subtreeWidths.get(child) + HORIZONTAL_GAP;
        }
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        g.scale(zoom, zoom);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g.setStroke(new BasicStroke(1.5f));
        g.setColor(JBColor.namedColor("Component.borderColor", new JBColor(0xA8B3C7, 0x596273)));
        edges.forEach(edge -> drawEdge(g, edge));
        visualNodes.forEach(node -> drawNode(g, node));
        g.dispose();
    }

    private void drawEdge(Graphics2D g, Edge edge) {
        double startX = edge.parent.x + NODE_WIDTH / 2.0;
        double startY = edge.parent.y + NODE_HEIGHT;
        double endX = edge.child.x + NODE_WIDTH / 2.0;
        double endY = edge.child.y;
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
    }

    private void drawNode(Graphics2D g, VisualNode visual) {
        AstNode node = visual.node;
        Color fill = colorFor(node.kind());
        RoundRectangle2D box = new RoundRectangle2D.Double(
                visual.x,
                visual.y,
                NODE_WIDTH,
                NODE_HEIGHT,
                12,
                12
        );

        g.setColor(fill);
        g.fill(box);
        g.setStroke(new BasicStroke(node == selectedNode ? 3f : 1.5f));
        g.setColor(node == selectedNode
                ? JBColor.namedColor("Focus.color", new JBColor(0x3574F0, 0x548AF7))
                : fill.darker());
        g.draw(box);

        g.setColor(contrastColor(fill));
        Font originalFont = g.getFont();
        g.setFont(originalFont.deriveFont(Font.BOLD));
        drawCenteredEllipsized(g, node.name(), visual.x + 10, visual.y + 10, NODE_WIDTH - 20);
        g.setFont(originalFont.deriveFont(Math.max(10f, originalFont.getSize2D() - 1f)));
        drawCenteredEllipsized(g, kindLabel(node.kind()), visual.x + 10, visual.y + 31, NODE_WIDTH - 20);
        g.setFont(originalFont);
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

    private AstNode findNode(Point point) {
        Point transformed = inverseTransform(point);
        for (int i = visualNodes.size() - 1; i >= 0; i--) {
            VisualNode visual = visualNodes.get(i);
            if (new RoundRectangle2D.Double(visual.x, visual.y, NODE_WIDTH, NODE_HEIGHT, 12, 12)
                    .contains(transformed)) {
                return visual.node;
            }
        }
        return null;
    }

    private void selectNode(AstNode node, boolean scrollToNode) {
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

    private Point inverseTransform(Point point) {
        try {
            return new Point((int) (point.x / zoom), (int) (point.y / zoom));
        } catch (RuntimeException ignored) {
            return point;
        }
    }

    @Override
    public String getToolTipText(MouseEvent event) {
        AstNode node = findNode(event.getPoint());
        return node == null ? null : AstLensBundle.message("graph.tooltip", kindLabel(node.kind()), node.name());
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

    private record VisualNode(AstNode node, int x, int y, int depth) {
    }

    private record Edge(VisualNode parent, VisualNode child) {
    }
}
