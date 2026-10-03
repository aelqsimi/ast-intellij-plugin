package com.aelqsimi.ast.ui;

import com.aelqsimi.ast.AstLensBundle;
import com.aelqsimi.ast.model.AstNode;
import com.intellij.ui.JBColor;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.Comparator;
import java.util.function.Consumer;

public final class AstGraphCanvas extends JComponent {
    private static final int NODE_WIDTH = AstGraphLayout.NODE_WIDTH;
    private static final int NODE_HEIGHT = AstGraphLayout.NODE_HEIGHT;

    private final Consumer<AstNode> navigator;
    private AstGraphLayout graphLayout = AstGraphLayout.empty();
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

    void setLayout(AstGraphLayout layout) {
        graphLayout = layout == null ? AstGraphLayout.empty() : layout;
        selectedNode = null;
        Dimension logicalSize = graphLayout.logicalSize();
        setPreferredSize(new Dimension(
                (int) (logicalSize.width * zoom),
                (int) (logicalSize.height * zoom)
        ));
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
        Rectangle2D visibleArea = visibleArea(graphics.getClipBounds());
        Graphics2D g = (Graphics2D) graphics.create();
        g.scale(zoom, zoom);
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
                effectiveClip.x / zoom,
                effectiveClip.y / zoom,
                effectiveClip.width / zoom,
                effectiveClip.height / zoom
        );
    }

    private void drawEdge(Graphics2D g, AstGraphLayout.Edge edge) {
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
    }

    private void drawNode(Graphics2D g, AstGraphLayout.Node visual) {
        AstNode node = visual.node();
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
        g.setStroke(new BasicStroke(node == selectedNode ? 3f : 1.5f));
        g.setColor(node == selectedNode
                ? JBColor.namedColor("Focus.color", new JBColor(0x3574F0, 0x548AF7))
                : fill.darker());
        g.draw(box);

        g.setColor(contrastColor(fill));
        Font originalFont = g.getFont();
        g.setFont(originalFont.deriveFont(Font.BOLD));
        drawCenteredEllipsized(g, node.name(), visual.x() + 10, visual.y() + 10, NODE_WIDTH - 20);
        g.setFont(originalFont.deriveFont(Math.max(10f, originalFont.getSize2D() - 1f)));
        drawCenteredEllipsized(g, kindLabel(node.kind()), visual.x() + 10, visual.y() + 31, NODE_WIDTH - 20);
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
        for (int i = graphLayout.nodes().size() - 1; i >= 0; i--) {
            AstGraphLayout.Node visual = graphLayout.nodes().get(i);
            if (new RoundRectangle2D.Double(visual.x(), visual.y(), NODE_WIDTH, NODE_HEIGHT, 12, 12)
                    .contains(transformed)) {
                return visual.node();
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
        graphLayout.nodes().stream()
                .filter(visual -> visual.node() == node)
                .findFirst()
                .ifPresent(visual -> scrollRectToVisible(new Rectangle(
                        (int) (visual.x() * zoom) - 20,
                        (int) (visual.y() * zoom) - 20,
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

}
