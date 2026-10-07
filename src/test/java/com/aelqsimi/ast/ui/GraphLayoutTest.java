package com.aelqsimi.ast.ui;

import com.aelqsimi.ast.model.AstNode;
import com.aelqsimi.ast.model.CallGraph;
import com.aelqsimi.ast.model.CallGraphEdge;
import com.aelqsimi.ast.model.CallGraphNode;
import org.junit.Test;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class GraphLayoutTest {
    private static AstNode astTree() {
        List<AstNode> methods = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            AstNode call = new AstNode(
                    AstNode.Kind.CALL,
                    "call" + index,
                    index * 20 + 5,
                    index * 20 + 10,
                    List.of()
            );
            methods.add(new AstNode(
                    AstNode.Kind.METHOD,
                    "method" + index,
                    index * 20,
                    index * 20 + 15,
                    List.of(call)
            ));
        }
        AstNode type = new AstNode(AstNode.Kind.CLASS, "Sample", 0, 100, methods);
        return new AstNode(AstNode.Kind.FILE, "Sample.java", 0, 100, List.of(type));
    }

    private static void drag(JComponent component, Point start, Point end) {
        component.dispatchEvent(mouseEvent(
                component,
                MouseEvent.MOUSE_PRESSED,
                start,
                1,
                MouseEvent.BUTTON1,
                MouseEvent.BUTTON1_DOWN_MASK
        ));
        component.dispatchEvent(mouseEvent(
                component,
                MouseEvent.MOUSE_DRAGGED,
                end,
                0,
                MouseEvent.NOBUTTON,
                MouseEvent.BUTTON1_DOWN_MASK
        ));
        component.dispatchEvent(mouseEvent(
                component,
                MouseEvent.MOUSE_RELEASED,
                end,
                1,
                MouseEvent.BUTTON1,
                0
        ));
    }

    private static void click(JComponent component, Point point) {
        component.dispatchEvent(mouseEvent(
                component,
                MouseEvent.MOUSE_PRESSED,
                point,
                1,
                MouseEvent.BUTTON1,
                MouseEvent.BUTTON1_DOWN_MASK
        ));
        component.dispatchEvent(mouseEvent(
                component,
                MouseEvent.MOUSE_RELEASED,
                point,
                1,
                MouseEvent.BUTTON1,
                0
        ));
        component.dispatchEvent(mouseEvent(
                component,
                MouseEvent.MOUSE_CLICKED,
                point,
                1,
                MouseEvent.BUTTON1,
                0
        ));
    }

    private static Point center(Rectangle rectangle) {
        return new Point(
                rectangle.x + rectangle.width / 2,
                rectangle.y + rectangle.height / 2
        );
    }

    private static void paintOffscreen(JComponent component) {
        BufferedImage image = new BufferedImage(
                component.getWidth(),
                component.getHeight(),
                BufferedImage.TYPE_INT_ARGB
        );
        Graphics2D graphics = image.createGraphics();
        try {
            component.paint(graphics);
        } finally {
            graphics.dispose();
        }
    }

    private static MouseEvent mouseEvent(
            Component component,
            int id,
            Point point,
            int clickCount,
            int button,
            int modifiers
    ) {
        return new MouseEvent(
                component,
                id,
                System.currentTimeMillis(),
                modifiers,
                point.x,
                point.y,
                clickCount,
                false,
                button
        );
    }

    @Test
    public void astLayoutCanBeCalculatedOffTheEdtAndCulledToTheViewport() throws Exception {
        AstNode root = astTree();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<AstGraphLayout> future = executor.submit(() -> AstGraphLayout.calculate(root));
            AstGraphLayout layout = future.get();

            assertNotNull(layout);
            assertEquals(12, layout.nodes().size());
            assertEquals(11, layout.edges().size());
            Rectangle viewport = layout.nodes().getFirst().bounds();
            long visibleNodes = layout.nodes().stream()
                    .filter(node -> node.bounds().intersects(viewport))
                    .count();
            assertEquals(1, visibleNodes);
            assertTrue(layout.logicalSize().width > AstGraphLayout.NODE_WIDTH);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void callGraphLayoutCanBeCalculatedOffTheEdtAndCulledToTheViewport() throws Exception {
        List<CallGraphNode> nodes = new ArrayList<>();
        List<CallGraphEdge> edges = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            nodes.add(new CallGraphNode(
                    "method-" + index,
                    "method" + index + "()",
                    CallGraphNode.Kind.INTERNAL,
                    null,
                    index * 10,
                    index * 10 + 5
            ));
            edges.add(new CallGraphEdge(
                    "method-" + index,
                    "method-" + ((index + 1) % 12),
                    1
            ));
        }

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<CallGraphLayout> future = executor.submit(() ->
                    CallGraphLayout.calculate(new CallGraph(nodes, edges))
            );
            CallGraphLayout layout = future.get();

            assertNotNull(layout);
            assertEquals(12, layout.nodes().size());
            assertEquals(12, layout.edges().size());
            Rectangle viewport = layout.nodes().getFirst().bounds();
            long visibleNodes = layout.nodes().stream()
                    .filter(node -> node.bounds().intersects(viewport))
                    .count();
            assertTrue(visibleNodes >= 1);
            assertTrue(visibleNodes < layout.nodes().size());
            assertFalse(layout.logicalSize().equals(CallGraphLayout.empty().logicalSize()));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void astFocusKeepsTheSelectedNeighborhoodTogether() {
        AstNode root = astTree();
        AstNode selected = root.children().getFirst();
        AstGraphLayout layout = AstGraphLayout.calculate(root);

        AstGraphLayout.Focus focus = layout.focus(selected);

        assertEquals(layout.nodes().size(), focus.layout().nodes().size());
        assertEquals(7, focus.nodes().size());
        AstGraphLayout.Node selectedVisual = focus.layout().nodes().stream()
                .filter(node -> node.node() == selected)
                .findFirst()
                .orElseThrow();
        assertEquals(
                focus.layout().logicalSize().width / 2 - AstGraphLayout.NODE_WIDTH / 2,
                selectedVisual.x()
        );
        assertTrue(focus.bounds().contains(selectedVisual.bounds()));
    }

    @Test
    public void callGraphFocusHighlightsAndCompactsDirectNeighbors() {
        List<CallGraphNode> nodes = new ArrayList<>();
        List<CallGraphEdge> edges = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            nodes.add(new CallGraphNode(
                    "method-" + index,
                    "method" + index + "()",
                    CallGraphNode.Kind.INTERNAL,
                    null,
                    index * 10,
                    index * 10 + 5
            ));
            edges.add(new CallGraphEdge(
                    "method-" + index,
                    "method-" + ((index + 1) % 12),
                    1
            ));
        }
        CallGraphLayout layout = CallGraphLayout.calculate(new CallGraph(nodes, edges));

        CallGraphLayout.Focus focus = layout.focus("method-0");

        assertEquals(Set.of("method-0", "method-1", "method-11"), focus.nodeIds());
        assertEquals(layout.nodes().size(), focus.layout().nodes().size());
        CallGraphLayout.Node selected = focus.layout().nodes().stream()
                .filter(node -> node.node().id().equals("method-0"))
                .findFirst()
                .orElseThrow();
        assertEquals(
                focus.layout().logicalSize().width / 2 - CallGraphLayout.NODE_WIDTH / 2,
                selected.x()
        );
        assertTrue(focus.bounds().contains(selected.bounds()));
    }

    @Test
    public void movingAnAstNodeKeepsItsEdgesConnected() {
        AstNode root = astTree();
        AstNode movedModel = root.children().getFirst();
        AstGraphLayout original = AstGraphLayout.calculate(root);
        int x = original.logicalSize().width + 120;
        int y = original.logicalSize().height + 80;

        AstGraphLayout moved = original.moveNode(movedModel, x, y);

        AstGraphLayout.Node movedVisual = moved.nodes().stream()
                .filter(node -> node.node() == movedModel)
                .findFirst()
                .orElseThrow();
        AstGraphLayout.Edge connectedEdge = moved.edges().stream()
                .filter(edge -> edge.child().node() == movedModel)
                .findFirst()
                .orElseThrow();
        assertEquals(x, movedVisual.x());
        assertEquals(y, movedVisual.y());
        assertSame(movedVisual, connectedEdge.child());
        assertTrue(moved.logicalSize().width >= x + AstGraphLayout.NODE_WIDTH);
        assertTrue(moved.logicalSize().height >= y + AstGraphLayout.NODE_HEIGHT);
    }

    @Test
    public void movingACallGraphNodeKeepsEdgeMetadataAndEndpoints() {
        CallGraphNode source = new CallGraphNode(
                "source",
                "source()",
                CallGraphNode.Kind.INTERNAL,
                null,
                0,
                10
        );
        CallGraphNode target = new CallGraphNode(
                "target",
                "target()",
                CallGraphNode.Kind.INTERNAL,
                null,
                11,
                20
        );
        CallGraphLayout original = CallGraphLayout.calculate(new CallGraph(
                List.of(source, target),
                List.of(new CallGraphEdge("source", "target", 3, "calls"))
        ));
        int x = original.logicalSize().width + 150;
        int y = original.logicalSize().height + 90;

        CallGraphLayout moved = original.moveNode("target", x, y);

        CallGraphLayout.Node movedVisual = moved.nodes().stream()
                .filter(node -> node.node().id().equals("target"))
                .findFirst()
                .orElseThrow();
        CallGraphLayout.Edge connectedEdge = moved.edges().getFirst();
        assertEquals(x, movedVisual.x());
        assertEquals(y, movedVisual.y());
        assertSame(movedVisual, connectedEdge.target());
        assertEquals(3, connectedEdge.callCount());
        assertEquals("calls", connectedEdge.label());
        assertTrue(moved.logicalSize().width >= x + CallGraphLayout.NODE_WIDTH);
        assertTrue(moved.logicalSize().height >= y + CallGraphLayout.NODE_HEIGHT);
    }

    @Test
    public void astCanvasDragsNodesWithoutTriggeringNavigation() throws Exception {
        AtomicInteger navigationCount = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> {
            AstGraphLayout layout = AstGraphLayout.calculate(astTree());
            AstGraphCanvas canvas = new AstGraphCanvas(node -> navigationCount.incrementAndGet());
            canvas.setLayout(layout);
            canvas.setSize(canvas.getPreferredSize());
            AstGraphLayout.Node visual = layout.nodes().getFirst();
            Point start = new Point(visual.x() + 20, visual.y() + 20);
            Point end = new Point(layout.logicalSize().width + 240, layout.logicalSize().height + 180);
            Dimension initialSize = canvas.getPreferredSize();

            drag(canvas, start, end);
            canvas.dispatchEvent(mouseEvent(canvas, MouseEvent.MOUSE_CLICKED, end, 2, MouseEvent.BUTTON1, 0));

            assertTrue(canvas.getPreferredSize().width > initialSize.width);
            assertTrue(canvas.getPreferredSize().height > initialSize.height);
            assertEquals(Cursor.MOVE_CURSOR, canvas.getCursor().getType());
            assertEquals(0, navigationCount.get());
        });
    }

    @Test
    public void callGraphCanvasDragsNodesAndExpandsItsViewport() throws Exception {
        AtomicInteger navigationCount = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> {
            CallGraphNode source = new CallGraphNode(
                    "source", "source()", CallGraphNode.Kind.INTERNAL, null, 0, 10
            );
            CallGraphNode target = new CallGraphNode(
                    "target", "target()", CallGraphNode.Kind.INTERNAL, null, 11, 20
            );
            CallGraphLayout layout = CallGraphLayout.calculate(new CallGraph(
                    List.of(source, target),
                    List.of(new CallGraphEdge("source", "target", 1, "calls"))
            ));
            CallGraphCanvas canvas = new CallGraphCanvas(node -> navigationCount.incrementAndGet());
            canvas.setLayout(layout);
            canvas.setSize(canvas.getPreferredSize());
            CallGraphLayout.Node visual = layout.nodes().getFirst();
            Point start = new Point(visual.x() + 20, visual.y() + 20);
            Point end = new Point(layout.logicalSize().width + 260, layout.logicalSize().height + 200);
            Dimension initialSize = canvas.getPreferredSize();

            drag(canvas, start, end);
            canvas.dispatchEvent(mouseEvent(canvas, MouseEvent.MOUSE_CLICKED, end, 2, MouseEvent.BUTTON1, 0));

            assertTrue(canvas.getPreferredSize().width > initialSize.width);
            assertTrue(canvas.getPreferredSize().height > initialSize.height);
            assertEquals(Cursor.MOVE_CURSOR, canvas.getCursor().getType());
            assertEquals(0, navigationCount.get());
        });
    }

    @Test
    public void astNodeGoToCodeControlNavigatesWithoutStartingADrag() throws Exception {
        AtomicInteger navigationCount = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> {
            AstGraphLayout layout = AstGraphLayout.calculate(astTree());
            AstGraphCanvas canvas = new AstGraphCanvas(node -> navigationCount.incrementAndGet());
            canvas.setLayout(layout);
            canvas.setSize(canvas.getPreferredSize());
            AstGraphLayout.Node visual = layout.nodes().getFirst();
            Rectangle controlBounds = GraphNodeControls.bounds(
                    GraphNodeControls.Action.NAVIGATE,
                    visual.x(),
                    visual.y(),
                    AstGraphLayout.NODE_WIDTH
            );
            Point control = center(controlBounds);
            Dimension initialSize = canvas.getPreferredSize();

            canvas.dispatchEvent(mouseEvent(
                    canvas,
                    MouseEvent.MOUSE_MOVED,
                    control,
                    0,
                    MouseEvent.NOBUTTON,
                    0
            ));
            click(canvas, control);
            paintOffscreen(canvas);

            assertEquals(Cursor.HAND_CURSOR, canvas.getCursor().getType());
            assertEquals(initialSize, canvas.getPreferredSize());
            assertEquals(1, navigationCount.get());
        });
    }

    @Test
    public void callGraphNodeControlsNeverStartDraggingTheNode() throws Exception {
        AtomicInteger navigationCount = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> {
            CallGraphNode node = new CallGraphNode(
                    "source", "source()", CallGraphNode.Kind.INTERNAL, null, 0, 10
            );
            CallGraphLayout layout = CallGraphLayout.calculate(new CallGraph(List.of(node), List.of()));
            CallGraphCanvas canvas = new CallGraphCanvas(ignored -> navigationCount.incrementAndGet());
            canvas.setLayout(layout);
            canvas.setSize(canvas.getPreferredSize());
            CallGraphLayout.Node visual = layout.nodes().getFirst();
            Point focusControl = center(GraphNodeControls.bounds(
                    GraphNodeControls.Action.FOCUS,
                    visual.x(),
                    visual.y(),
                    CallGraphLayout.NODE_WIDTH
            ));
            Point farAway = new Point(layout.logicalSize().width + 300, layout.logicalSize().height + 240);
            Dimension initialSize = canvas.getPreferredSize();

            drag(canvas, focusControl, farAway);
            canvas.dispatchEvent(mouseEvent(
                    canvas,
                    MouseEvent.MOUSE_CLICKED,
                    farAway,
                    1,
                    MouseEvent.BUTTON1,
                    0
            ));

            assertEquals(initialSize, canvas.getPreferredSize());
            assertEquals(0, navigationCount.get());
        });
    }

    @Test
    public void largeCallGraphUsesCompactConcentricRings() {
        List<CallGraphNode> nodes = new ArrayList<>();
        List<CallGraphEdge> edges = new ArrayList<>();
        for (int index = 0; index < 500; index++) {
            nodes.add(new CallGraphNode(
                    "method-" + index,
                    "method" + index + "()",
                    CallGraphNode.Kind.INTERNAL,
                    null,
                    index,
                    index + 1
            ));
            if (index > 0) {
                edges.add(new CallGraphEdge("method-0", "method-" + index, 1));
            }
        }

        CallGraphLayout layout = CallGraphLayout.calculate(new CallGraph(nodes, edges));
        CallGraphLayout.Node hub = layout.nodes().stream()
                .filter(node -> node.node().id().equals("method-0"))
                .findFirst()
                .orElseThrow();

        assertEquals(layout.logicalSize().width / 2 - CallGraphLayout.NODE_WIDTH / 2, hub.x());
        assertEquals(layout.logicalSize().height / 2 - CallGraphLayout.NODE_HEIGHT / 2, hub.y());
        assertTrue("The old single ring was wider than 48,000 px", layout.logicalSize().width < 10_000);
        assertTrue("The old single ring was taller than 48,000 px", layout.logicalSize().height < 10_000);
    }
}
