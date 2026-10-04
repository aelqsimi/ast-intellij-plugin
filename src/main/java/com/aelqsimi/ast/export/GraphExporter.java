package com.aelqsimi.ast.export;

import com.aelqsimi.ast.model.*;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public final class GraphExporter {
    private GraphExporter() {
    }

    public static String exportStructure(AstNode root, Format format) {
        return switch (format) {
            case JSON -> structureJson(root);
            case MERMAID -> structureMermaid(root);
            case MERMAID_HTML -> largeMermaidHtml("AST Lens Structure", structureMermaid(root));
            case GRAPHVIZ -> structureGraphviz(root);
        };
    }

    public static String exportDirectedGraph(CallGraph graph, String title, Format format) {
        return switch (format) {
            case JSON -> directedJson(graph, title);
            case MERMAID -> directedMermaid(graph, title);
            case MERMAID_HTML -> largeMermaidHtml(title, directedMermaid(graph, title));
            case GRAPHVIZ -> directedGraphviz(graph, title);
        };
    }

    public static String exportComparison(SyntaxComparison comparison, Format format) {
        return switch (format) {
            case JSON -> comparisonJson(comparison);
            case MERMAID -> comparisonMermaid(comparison);
            case MERMAID_HTML -> largeMermaidHtml("PSI / UAST", comparisonMermaid(comparison));
            case GRAPHVIZ -> comparisonGraphviz(comparison);
        };
    }

    public static String exportCodeHealth(CodeHealthReport report, Format format) {
        return switch (format) {
            case JSON -> codeHealthJson(report);
            case MERMAID -> codeHealthMermaid(report);
            case MERMAID_HTML -> largeMermaidHtml("AST Lens Code Health", codeHealthMermaid(report));
            case GRAPHVIZ -> codeHealthGraphviz(report);
        };
    }

    private static String largeMermaidHtml(String title, String diagram) {
        int edgeCount = diagram.lines()
                .mapToInt(line -> line.contains(" -->") || line.contains(" -.->") ? 1 : 0)
                .sum();
        int maxEdges = Math.max(1_000, edgeCount + 100);
        int maxTextSize = Math.max(100_000, diagram.length() + 10_000);
        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>%s</title>
                  <style>
                    html, body { margin: 0; min-width: 100%%; min-height: 100%%; background: #fff; }
                    body { font-family: system-ui, sans-serif; overflow: auto; }
                    main { box-sizing: border-box; min-width: 100vw; min-height: 100vh; padding: 24px; text-align: center; }
                    .mermaid { display: inline-block; margin: 0 auto; text-align: left; }
                    #error { color: #b00020; white-space: pre-wrap; }
                  </style>
                </head>
                <body>
                  <main>
                    <div id="error" role="alert"></div>
                    <pre class="mermaid">%s</pre>
                  </main>
                  <script type="module">
                    import mermaid from 'https://cdn.jsdelivr.net/npm/mermaid@12.1.0/dist/mermaid.esm.min.mjs';
                    mermaid.initialize({
                      startOnLoad: false,
                      maxEdges: %d,
                      maxTextSize: %d,
                      layout: 'dagre',
                      flowchart: { nodeSpacing: 20, rankSpacing: 30, useMaxWidth: false }
                    });
                    mermaid.run().catch(error => {
                      document.getElementById('error').textContent =
                        'Unable to render the Mermaid graph: ' + (error?.message ?? error);
                    });
                  </script>
                </body>
                </html>
                """.formatted(html(title), html(diagram), maxEdges, maxTextSize);
    }

    private static String structureJson(AstNode root) {
        StringBuilder output = new StringBuilder("{\n  \"type\": \"structure\",\n  \"root\": ");
        appendAstNodeJson(output, root, 1);
        return output.append("\n}\n").toString();
    }

    private static void appendAstNodeJson(StringBuilder output, AstNode node, int depth) {
        if (node == null) {
            output.append("null");
            return;
        }
        output.append("{\n")
                .append(indent(depth + 1)).append("\"kind\": ").append(json(node.kind().name())).append(",\n")
                .append(indent(depth + 1)).append("\"name\": ").append(json(node.name())).append(",\n")
                .append(indent(depth + 1)).append("\"startOffset\": ").append(node.startOffset()).append(",\n")
                .append(indent(depth + 1)).append("\"endOffset\": ").append(node.endOffset()).append(",\n")
                .append(indent(depth + 1)).append("\"children\": [");
        if (!node.children().isEmpty()) {
            output.append('\n');
            for (int index = 0; index < node.children().size(); index++) {
                output.append(indent(depth + 2));
                appendAstNodeJson(output, node.children().get(index), depth + 2);
                output.append(index + 1 == node.children().size() ? "\n" : ",\n");
            }
            output.append(indent(depth + 1));
        }
        output.append("]\n").append(indent(depth)).append('}');
    }

    private static String directedJson(CallGraph graph, String title) {
        StringBuilder output = new StringBuilder("{\n")
                .append("  \"type\": \"directedGraph\",\n")
                .append("  \"title\": ").append(json(title)).append(",\n")
                .append("  \"nodes\": [");
        if (!graph.nodes().isEmpty()) {
            output.append('\n');
        }
        for (int index = 0; index < graph.nodes().size(); index++) {
            CallGraphNode node = graph.nodes().get(index);
            output.append("    {\"id\": ").append(json(node.id()))
                    .append(", \"label\": ").append(json(node.label()))
                    .append(", \"kind\": ").append(json(node.kind().name()))
                    .append(", \"file\": ").append(node.file() == null ? "null" : json(node.file().getPath()))
                    .append(", \"startOffset\": ").append(node.startOffset())
                    .append(", \"endOffset\": ").append(node.endOffset())
                    .append('}')
                    .append(index + 1 == graph.nodes().size() ? "\n" : ",\n");
        }
        output.append("  ],\n  \"edges\": [");
        if (!graph.edges().isEmpty()) {
            output.append('\n');
        }
        for (int index = 0; index < graph.edges().size(); index++) {
            CallGraphEdge edge = graph.edges().get(index);
            output.append("    {\"source\": ").append(json(edge.sourceId()))
                    .append(", \"target\": ").append(json(edge.targetId()))
                    .append(", \"count\": ").append(edge.callCount())
                    .append(", \"label\": ").append(json(edge.label()))
                    .append('}')
                    .append(index + 1 == graph.edges().size() ? "\n" : ",\n");
        }
        return output.append("  ]\n}\n").toString();
    }

    private static String comparisonJson(SyntaxComparison comparison) {
        StringBuilder output = new StringBuilder("{\n  \"type\": \"psiUastComparison\",\n  \"psi\": ");
        appendSyntaxNodeJson(output, comparison.psiRoot(), 1);
        output.append(",\n  \"uast\": ");
        appendSyntaxNodeJson(output, comparison.uastRoot(), 1);
        return output.append("\n}\n").toString();
    }

    private static void appendSyntaxNodeJson(StringBuilder output, SyntaxTreeNode node, int depth) {
        if (node == null) {
            output.append("null");
            return;
        }
        output.append("{\n")
                .append(indent(depth + 1)).append("\"type\": ").append(json(node.type())).append(",\n")
                .append(indent(depth + 1)).append("\"excerpt\": ").append(json(node.excerpt())).append(",\n")
                .append(indent(depth + 1)).append("\"category\": ").append(json(node.category().name())).append(",\n")
                .append(indent(depth + 1)).append("\"startOffset\": ").append(node.startOffset()).append(",\n")
                .append(indent(depth + 1)).append("\"endOffset\": ").append(node.endOffset()).append(",\n")
                .append(indent(depth + 1)).append("\"children\": [");
        if (!node.children().isEmpty()) {
            output.append('\n');
            for (int index = 0; index < node.children().size(); index++) {
                output.append(indent(depth + 2));
                appendSyntaxNodeJson(output, node.children().get(index), depth + 2);
                output.append(index + 1 == node.children().size() ? "\n" : ",\n");
            }
            output.append(indent(depth + 1));
        }
        output.append("]\n").append(indent(depth)).append('}');
    }

    private static String structureMermaid(AstNode root) {
        StringBuilder output = new StringBuilder("flowchart TD\n");
        Map<AstNode, String> ids = new IdentityHashMap<>();
        appendAstMermaid(output, root, ids, new Counter(), null);
        return output.toString();
    }

    private static void appendAstMermaid(
            StringBuilder output,
            AstNode node,
            Map<AstNode, String> ids,
            Counter counter,
            String parentId
    ) {
        if (node == null) {
            return;
        }
        String id = ids.computeIfAbsent(node, ignored -> "n" + counter.next());
        output.append("  ").append(id).append("[\"")
                .append(mermaid(node.kind() + ": " + node.name())).append("\"]\n");
        if (parentId != null) {
            output.append("  ").append(parentId).append(" --> ").append(id).append('\n');
        }
        node.children().forEach(child -> appendAstMermaid(output, child, ids, counter, id));
    }

    private static String directedMermaid(CallGraph graph, String title) {
        StringBuilder output = new StringBuilder("---\ntitle: ")
                .append(mermaid(title)).append("\n---\nflowchart LR\n");
        Map<String, String> ids = sequentialIds(graph);
        for (CallGraphNode node : graph.nodes()) {
            output.append("  ").append(ids.get(node.id())).append("[\"")
                    .append(mermaid(node.label())).append("\"]\n");
        }
        for (CallGraphEdge edge : graph.edges()) {
            String label = edgeDisplayLabel(edge);
            String mermaidLabel = label.isEmpty() ? "" : "|" + mermaid(label) + "|";
            output.append("  ").append(ids.get(edge.sourceId())).append(" -->")
                    .append(mermaidLabel).append(' ').append(ids.get(edge.targetId())).append('\n');
        }
        return output.toString();
    }

    private static String comparisonMermaid(SyntaxComparison comparison) {
        StringBuilder output = new StringBuilder("flowchart LR\n  subgraph PSI\n");
        appendSyntaxMermaid(output, comparison.psiRoot(), "p", new Counter(), null, "    ");
        output.append("  end\n  subgraph UAST\n");
        appendSyntaxMermaid(output, comparison.uastRoot(), "u", new Counter(), null, "    ");
        return output.append("  end\n").toString();
    }

    private static void appendSyntaxMermaid(
            StringBuilder output,
            SyntaxTreeNode node,
            String prefix,
            Counter counter,
            String parentId,
            String indentation
    ) {
        if (node == null) {
            return;
        }
        String id = prefix + counter.next();
        output.append(indentation).append(id).append("[\"")
                .append(mermaid(node.type())).append("\"]\n");
        if (parentId != null) {
            output.append(indentation).append(parentId).append(" --> ").append(id).append('\n');
        }
        node.children().forEach(child ->
                appendSyntaxMermaid(output, child, prefix, counter, id, indentation));
    }

    private static String structureGraphviz(AstNode root) {
        StringBuilder output = graphvizHeader("AST Lens Structure", "TB");
        appendAstGraphviz(output, root, new IdentityHashMap<>(), new Counter(), null);
        return output.append("}\n").toString();
    }

    private static void appendAstGraphviz(
            StringBuilder output,
            AstNode node,
            Map<AstNode, String> ids,
            Counter counter,
            String parentId
    ) {
        if (node == null) {
            return;
        }
        String id = ids.computeIfAbsent(node, ignored -> "n" + counter.next());
        output.append("  ").append(id).append(" [label=")
                .append(dot(node.kind() + ": " + node.name())).append("];\n");
        if (parentId != null) {
            output.append("  ").append(parentId).append(" -> ").append(id).append(";\n");
        }
        node.children().forEach(child -> appendAstGraphviz(output, child, ids, counter, id));
    }

    private static String directedGraphviz(CallGraph graph, String title) {
        StringBuilder output = graphvizHeader(title, "LR");
        Map<String, String> ids = sequentialIds(graph);
        for (CallGraphNode node : graph.nodes()) {
            output.append("  ").append(ids.get(node.id())).append(" [label=")
                    .append(dot(node.label())).append("];\n");
        }
        for (CallGraphEdge edge : graph.edges()) {
            output.append("  ").append(ids.get(edge.sourceId())).append(" -> ")
                    .append(ids.get(edge.targetId()));
            String label = edgeDisplayLabel(edge);
            if (!label.isEmpty()) {
                output.append(" [label=").append(dot(label)).append(']');
            }
            output.append(";\n");
        }
        return output.append("}\n").toString();
    }

    private static String comparisonGraphviz(SyntaxComparison comparison) {
        StringBuilder output = graphvizHeader("PSI / UAST", "LR")
                .append("  subgraph cluster_psi { label=\"PSI\";\n");
        appendSyntaxGraphviz(output, comparison.psiRoot(), "p", new Counter(), null, "    ");
        output.append("  }\n  subgraph cluster_uast { label=\"UAST\";\n");
        appendSyntaxGraphviz(output, comparison.uastRoot(), "u", new Counter(), null, "    ");
        return output.append("  }\n}\n").toString();
    }

    private static void appendSyntaxGraphviz(
            StringBuilder output,
            SyntaxTreeNode node,
            String prefix,
            Counter counter,
            String parentId,
            String indentation
    ) {
        if (node == null) {
            return;
        }
        String id = prefix + counter.next();
        output.append(indentation).append(id).append(" [label=").append(dot(node.type())).append("];\n");
        if (parentId != null) {
            output.append(indentation).append(parentId).append(" -> ").append(id).append(";\n");
        }
        node.children().forEach(child ->
                appendSyntaxGraphviz(output, child, prefix, counter, id, indentation));
    }

    private static String codeHealthJson(CodeHealthReport report) {
        StringBuilder output = new StringBuilder("{\n")
                .append("  \"type\": \"codeHealth\",\n")
                .append("  \"thresholds\": {\"classLines\": ")
                .append(report.classLineThreshold())
                .append(", \"methodComplexity\": ")
                .append(report.methodComplexityThreshold())
                .append("},\n  \"issues\": [");
        if (!report.issues().isEmpty()) {
            output.append('\n');
        }
        for (int index = 0; index < report.issues().size(); index++) {
            CodeHealthIssue issue = report.issues().get(index);
            output.append("    {\"kind\": ").append(json(issue.kind().name()))
                    .append(", \"symbol\": ").append(json(issue.symbol()))
                    .append(", \"value\": ").append(issue.value())
                    .append(", \"threshold\": ").append(issue.threshold())
                    .append(", \"participants\": [");
            for (int participant = 0; participant < issue.participants().size(); participant++) {
                if (participant > 0) {
                    output.append(", ");
                }
                output.append(json(issue.participants().get(participant)));
            }
            output.append("], \"file\": ")
                    .append(issue.file() == null ? "null" : json(issue.file().getPath()))
                    .append(", \"startOffset\": ").append(issue.startOffset())
                    .append(", \"endOffset\": ").append(issue.endOffset())
                    .append('}')
                    .append(index + 1 == report.issues().size() ? "\n" : ",\n");
        }
        return output.append("  ]\n}\n").toString();
    }

    private static String codeHealthMermaid(CodeHealthReport report) {
        StringBuilder output = new StringBuilder("flowchart TD\n");
        for (int index = 0; index < report.issues().size(); index++) {
            CodeHealthIssue issue = report.issues().get(index);
            String issueId = "i" + index;
            output.append("  ").append(issueId).append("[\"")
                    .append(mermaid(issue.kind().name() + ": " + issue.symbol()))
                    .append("\"]\n");
            if (issue.participants().isEmpty()) {
                String symbolId = "s" + index;
                output.append("  ").append(symbolId).append("[\"")
                        .append(mermaid(issue.symbol())).append("\"]\n")
                        .append("  ").append(issueId).append(" -->|\"")
                        .append(issue.value()).append(" > ").append(issue.threshold())
                        .append("\"| ").append(symbolId).append('\n');
            } else {
                appendMermaidCycle(output, issueId, issue.participants(), index);
            }
        }
        return output.toString();
    }

    private static void appendMermaidCycle(
            StringBuilder output,
            String issueId,
            java.util.List<String> participants,
            int issueIndex
    ) {
        for (int index = 0; index < participants.size(); index++) {
            String nodeId = "c" + issueIndex + "_" + index;
            output.append("  ").append(nodeId).append("[\"")
                    .append(mermaid(participants.get(index))).append("\"]\n");
            output.append("  ").append(issueId).append(" -.-> ").append(nodeId).append('\n');
        }
    }

    private static String codeHealthGraphviz(CodeHealthReport report) {
        StringBuilder output = graphvizHeader("AST Lens Code Health", "LR");
        for (int index = 0; index < report.issues().size(); index++) {
            CodeHealthIssue issue = report.issues().get(index);
            String issueId = "i" + index;
            output.append("  ").append(issueId).append(" [shape=note, fillcolor=\"#FFF3CD\", label=")
                    .append(dot(issue.kind().name() + ": " + issue.symbol())).append("];\n");
            if (issue.participants().isEmpty()) {
                String symbolId = "s" + index;
                output.append("  ").append(symbolId).append(" [label=")
                        .append(dot(issue.symbol())).append("];\n")
                        .append("  ").append(issueId).append(" -> ").append(symbolId)
                        .append(" [label=").append(dot(issue.value() + " > " + issue.threshold()))
                        .append("];\n");
            } else {
                for (int participant = 0; participant < issue.participants().size(); participant++) {
                    String nodeId = "c" + index + "_" + participant;
                    output.append("  ").append(nodeId).append(" [label=")
                            .append(dot(issue.participants().get(participant))).append("];\n")
                            .append("  ").append(issueId).append(" -> ").append(nodeId)
                            .append(" [style=dashed];\n");
                }
            }
        }
        return output.append("}\n").toString();
    }

    private static Map<String, String> sequentialIds(CallGraph graph) {
        Map<String, String> ids = new LinkedHashMap<>();
        for (int index = 0; index < graph.nodes().size(); index++) {
            ids.put(graph.nodes().get(index).id(), "n" + index);
        }
        return ids;
    }

    private static StringBuilder graphvizHeader(String title, String direction) {
        return new StringBuilder("digraph ")
                .append(dot(title)).append(" {\n")
                .append("  rankdir=").append(direction).append(";\n")
                .append("  node [shape=box, style=\"rounded,filled\", fillcolor=\"#E8F5E9\"];\n");
    }

    private static String indent(int depth) {
        return "  ".repeat(Math.max(0, depth));
    }

    private static String json(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder escaped = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.append('\"').toString();
    }

    private static String mermaid(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "&quot;")
                .replace("\r", " ").replace("\n", " ");
    }

    private static String edgeDisplayLabel(CallGraphEdge edge) {
        if (edge.label().isBlank()) {
            return edge.callCount() > 1 ? "×" + edge.callCount() : "";
        }
        return edge.callCount() > 1
                ? edge.label() + " ×" + edge.callCount()
                : edge.label();
    }

    private static String html(String value) {
        return value == null ? "" : value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String dot(String value) {
        return json(value == null ? "" : value);
    }

    public enum Format {
        JSON("JSON", "json"),
        MERMAID("Mermaid", "mmd"),
        MERMAID_HTML("Mermaid HTML (large graph)", "html"),
        GRAPHVIZ("Graphviz", "dot");

        private final String displayName;
        private final String extension;

        Format(String displayName, String extension) {
            this.displayName = displayName;
            this.extension = extension;
        }

        public String displayName() {
            return displayName;
        }

        public String extension() {
            return extension;
        }
    }

    private static final class Counter {
        private int value;

        private int next() {
            return value++;
        }
    }
}
