# AST Lens — IntelliJ Plugin

Visualize Java and Kotlin code as AST graphs.

AST Lens helps developers inspect source-code structure directly from the IDE by transforming
Java and Kotlin syntax trees into visual graphs.

- **Visual AST graphs** — inspect classes, functions, expressions, and syntax hierarchy visually.
- **Java and Kotlin** — analyze source code across JVM projects.
- **Structure exploration** — navigate parent/child relationships and understand complex code paths.
- **IDE workflow** — analyze code without leaving your development environment.
- **Graph export** — export visualizations as JSON, Mermaid, or Graphviz files.

AST Lens is useful for code exploration, architecture analysis, learning, debugging tooling,
and understanding unfamiliar codebases.


![ATS Lens Image](https://github.com/aelqsimi/ast-intellij-plugin/blob/develop/ASTLens%20.png?raw=true)

## Try the plugin

Marketplace : https://plugins.jetbrains.com/plugin/34773-ast-lens/ast-lens

1. Open the project in IntelliJ IDEA.
2. Synchronize the Gradle project if IntelliJ IDEA does not do so automatically.
3. Run the Gradle task `intellij platform > runIde`.
4. Open a Java or Kotlin file.
5. Open **AST Lens** from the tool window bar or choose **Tools > Open AST Lens**.
6. Click **Analyze**, then select **Structure**, **Call graph**, **Class dependencies**, **Package dependencies**, or
   **PSI / UAST comparison**. Each node provides a focus button and a go-to-code button. Moving the
   caret in the editor automatically selects and centers the corresponding node in the graph. Drag
   the rest of a node to rearrange the view manually; its connected edges follow it in real time.

The PSI/UAST comparison can hide whitespace and comments, punctuation, imports, and
synthetic UAST nodes.

The **Export…** button saves the active view as JSON (`.json`), Mermaid (`.mmd`), or
Graphviz (`.dot`). For diagrams above Mermaid's default 500-edge limit, choose **Mermaid HTML (large graph)**. The
generated standalone page raises the limit according to
the exported graph, uses tighter Mermaid spacing, and needs an internet connection when opened
to load Mermaid 12.1.0 from jsDelivr.

The **Method search** toolbar searches from the method under the caret for callers or callees.
The separate **Class search** toolbar finds dependent classes, parent classes, implemented
interfaces, and inheriting classes from the class under the caret. Results are navigable and
exportable like the other graphs. Relationship edges are explicitly labelled (`extends`,
`implements`, `depends on`, or `calls`); project structure edges use `contains`.

The **Code health** view detects classes longer than 500 lines, methods whose cyclomatic
complexity exceeds 10, and circular dependencies between classes or packages. Double-click
an issue to open the affected symbol. Reports can be exported as JSON, Mermaid, or Graphviz.

The **Analyze project** button scans every Java and Kotlin source file in the project. It
produces a package → file → class → method hierarchy and a project-wide call graph while
updating dependency and code-health results. Each project graph is limited to 750 nodes to
keep the IDE responsive; counters still cover the entire project.

The **Include project dependencies** option controls the analysis boundary. Project sources
are always included. When enabled, declared Gradle, Maven, and JAR libraries are included as
well; JDK/SDK symbols such as `java.lang.String` and undeclared external files are always
excluded. Disable the option to restrict every graph to project sources only.

The **Focus connected nodes** option makes large graphs easier to inspect. Selecting a node
highlights its direct neighbors and connecting edges, moves that local subgraph into a compact
layout, dims unrelated elements, and temporarily fits the focused nodes into the viewport.
Disabling the option restores the original layout and zoom.

Large directed graphs use a compact concentric layout: the most connected node is placed in the
center and the remaining nodes are distributed across nearby rings. Tree graphs also use reduced
horizontal and vertical spacing.

Project-wide analysis uses a shared incremental per-file cache. It recalculates only files
whose PSI `modificationStamp` changed, their semantic dependants, and files containing
unresolved references. SDK, library, and project-root changes invalidate the complete cache.
No PSI or UAST object is retained: the in-memory cache stores only immutable derived data.

## Useful commands

```powershell
.\gradlew.bat runIde
.\gradlew.bat performanceTest
.\gradlew.bat integrationTest
.\gradlew.bat buildPlugin
.\gradlew.bat verifyPlugin
```

`performanceTest` measures cold-cache analysis on projects containing 10, 50, and 100 files,
then measures a warm-cache run and a single-file modification. It writes the CSV report to
`build/reports/ast-lens-performance/performance-results.csv`. Safety budgets can be adjusted
with the `ast.lens.performance.maxColdMs`, `ast.lens.performance.maxWarmMs`, and
`ast.lens.performance.maxEditMs` system properties.

If HTTPS dependency resolution fails with a PKIX certificate error on Windows, create
`C:\Users\<user>\.gradle\gradle.properties` with the following content, then restart the
Gradle daemons:

```properties
org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8 -Djavax.net.ssl.trustStoreType=Windows-ROOT
```

```powershell
.\gradlew.bat --stop
.\gradlew.bat dependencies --configuration intellijPlatformDependency --refresh-dependencies
```

The installable archive generated by `buildPlugin` is available in `build/distributions`.

## Privacy

AST Lens analyzes code locally. It does not collect telemetry or transmit source code or
analysis results to an external service. It writes a file only when the user explicitly
starts an export. See the complete [privacy notice](PRIVACY.md).

## License

Copyright 2026 Abdelmounaim EL QSIMI.

AST Lens is licensed under the [Apache License 2.0](LICENSE).
