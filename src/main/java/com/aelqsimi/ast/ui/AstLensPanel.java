package com.aelqsimi.ast.ui;

import com.aelqsimi.ast.AstLensBundle;
import com.aelqsimi.ast.analysis.*;
import com.aelqsimi.ast.export.GraphExporter;
import com.aelqsimi.ast.model.*;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.ui.ScrollPaneFactory;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.function.Supplier;

public final class AstLensPanel extends JPanel implements Disposable {
    private static final Logger LOG = Logger.getInstance(AstLensPanel.class);
    private static final String STRUCTURE_CARD = "structure";
    private static final String CALL_GRAPH_CARD = "callGraph";
    private static final String CLASS_DEPENDENCIES_CARD = "classDependencies";
    private static final String PACKAGE_DEPENDENCIES_CARD = "packageDependencies";
    private static final String PSI_UAST_CARD = "psiUast";
    private static final String RELATIONSHIP_CARD = "relationship";
    private static final String CODE_HEALTH_CARD = "codeHealth";
    private static final String PROJECT_STRUCTURE_CARD = "projectStructure";
    private static final String PROJECT_CALL_GRAPH_CARD = "projectCallGraph";
    private static final int STRUCTURE_VIEW = 0;
    private static final int CALL_GRAPH_VIEW = 1;
    private static final int CLASS_DEPENDENCIES_VIEW = 2;
    private static final int PACKAGE_DEPENDENCIES_VIEW = 3;
    private static final int PSI_UAST_VIEW = 4;
    private static final int RELATIONSHIP_VIEW = 5;
    private static final int CODE_HEALTH_VIEW = 6;
    private static final int PROJECT_STRUCTURE_VIEW = 7;
    private static final int PROJECT_CALL_GRAPH_VIEW = 8;

    private final Project project;
    private final UastAnalyzer analyzer = new UastAnalyzer();
    private final CallGraphAnalyzer callGraphAnalyzer = new CallGraphAnalyzer();
    private final CodeHealthAnalyzer codeHealthAnalyzer = new CodeHealthAnalyzer();
    private final DependencyGraphAnalyzer dependencyGraphAnalyzer = new DependencyGraphAnalyzer();
    private final ProjectAnalyzer projectAnalyzer = new ProjectAnalyzer();
    private final RelationshipAnalyzer relationshipAnalyzer = new RelationshipAnalyzer();
    private final SyntaxComparisonAnalyzer syntaxComparisonAnalyzer = new SyntaxComparisonAnalyzer();
    private final AstGraphCanvas structureGraph;
    private final CallGraphCanvas callGraph;
    private final CallGraphCanvas classDependencyGraph;
    private final CallGraphCanvas packageDependencyGraph;
    private final CallGraphCanvas relationshipGraph;
    private final SyntaxComparisonPanel syntaxComparisonPanel;
    private final CodeHealthPanel codeHealthPanel;
    private final CallGraphCanvas projectStructureGraph;
    private final CallGraphCanvas projectCallGraph;
    private final CardLayout graphLayout = new CardLayout();
    private final JPanel graphContainer = new JPanel(graphLayout);
    private final JLabel status = new JLabel(AstLensBundle.message("status.open.file"));
    private final JButton exportButton = new JButton(AstLensBundle.message("button.export"));
    private final JComboBox<String> viewSelector;
    private final JComboBox<String> relationshipSelector;
    private int activeView = STRUCTURE_VIEW;
    private VirtualFile analyzedFile;
    private DependencyAnalysis currentDependencies;
    private PanelAnalysis currentAnalysis;
    private RelationshipResult currentRelationship;
    private RelationshipQuery currentRelationshipQuery;
    private CodeHealthReport currentCodeHealth;
    private ProjectAnalysis currentProjectAnalysis;

    public AstLensPanel(Project project) {
        super(new BorderLayout());
        getAccessibleContext().setAccessibleName("AST Lens content");
        this.project = project;
        structureGraph = new AstGraphCanvas(this::navigateTo);
        callGraph = new CallGraphCanvas(this::navigateToCall);
        classDependencyGraph = new CallGraphCanvas(
                this::navigateToCall,
                "dependency.graph.tooltip",
                "dependency.class.kind."
        );
        packageDependencyGraph = new CallGraphCanvas(
                this::navigateToCall,
                "dependency.graph.tooltip",
                "dependency.package.kind."
        );
        relationshipGraph = new CallGraphCanvas(
                this::navigateToCall,
                "relationship.graph.tooltip",
                "relationship.node.kind."
        );
        syntaxComparisonPanel = new SyntaxComparisonPanel(this::navigateToSyntaxNode);
        codeHealthPanel = new CodeHealthPanel(this::navigateToHealthIssue);
        projectStructureGraph = new CallGraphCanvas(
                this::navigateToCall,
                "project.graph.tooltip",
                "project.node.kind."
        );
        projectCallGraph = new CallGraphCanvas(this::navigateToCall);

        graphContainer.add(ScrollPaneFactory.createScrollPane(structureGraph, true), STRUCTURE_CARD);
        graphContainer.add(ScrollPaneFactory.createScrollPane(callGraph, true), CALL_GRAPH_CARD);
        graphContainer.add(
                ScrollPaneFactory.createScrollPane(classDependencyGraph, true),
                CLASS_DEPENDENCIES_CARD
        );
        graphContainer.add(
                ScrollPaneFactory.createScrollPane(packageDependencyGraph, true),
                PACKAGE_DEPENDENCIES_CARD
        );
        graphContainer.add(syntaxComparisonPanel, PSI_UAST_CARD);
        graphContainer.add(ScrollPaneFactory.createScrollPane(relationshipGraph, true), RELATIONSHIP_CARD);
        graphContainer.add(codeHealthPanel, CODE_HEALTH_CARD);
        graphContainer.add(
                ScrollPaneFactory.createScrollPane(projectStructureGraph, true),
                PROJECT_STRUCTURE_CARD
        );
        graphContainer.add(
                ScrollPaneFactory.createScrollPane(projectCallGraph, true),
                PROJECT_CALL_GRAPH_CARD
        );

        JButton analyze = new JButton(AstLensBundle.message("button.analyze"));
        analyze.addActionListener(event -> analyzeCurrentFile());
        JButton analyzeProject = new JButton(AstLensBundle.message("button.analyze.project"));
        analyzeProject.addActionListener(event -> analyzeProject());
        exportButton.setEnabled(false);
        exportButton.addActionListener(event -> exportCurrentView());
        viewSelector = new JComboBox<>(new String[]{
                AstLensBundle.message("view.structure"),
                AstLensBundle.message("view.call.graph"),
                AstLensBundle.message("view.class.dependencies"),
                AstLensBundle.message("view.package.dependencies"),
                AstLensBundle.message("view.psi.uast"),
                AstLensBundle.message("view.relationship.search"),
                AstLensBundle.message("view.code.health"),
                AstLensBundle.message("view.project.structure"),
                AstLensBundle.message("view.project.call.graph")
        });
        viewSelector.addActionListener(event -> {
            activeView = viewSelector.getSelectedIndex();
            graphLayout.show(graphContainer, cardFor(activeView));
            synchronizeWithCurrentCaret();
        });
        RelationshipQuery[] relationshipQueries = RelationshipQuery.values();
        relationshipSelector = new JComboBox<>(java.util.Arrays.stream(relationshipQueries)
                .map(query -> AstLensBundle.message(query.messageKey()))
                .toArray(String[]::new));
        JButton searchRelationship = new JButton(AstLensBundle.message("button.search.relationship"));
        searchRelationship.addActionListener(event -> searchRelationship());
        JButton zoomOut = new JButton("−");
        zoomOut.setToolTipText(AstLensBundle.message("button.zoom.out"));
        zoomOut.addActionListener(event -> zoomOut());
        JButton resetZoom = new JButton("100 %");
        resetZoom.setToolTipText(AstLensBundle.message("button.zoom.reset"));
        resetZoom.addActionListener(event -> resetZoom());
        JButton zoomIn = new JButton("+");
        zoomIn.setToolTipText(AstLensBundle.message("button.zoom.in"));
        zoomIn.addActionListener(event -> zoomIn());

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        controls.add(analyze);
        controls.add(analyzeProject);
        controls.add(exportButton);
        controls.add(viewSelector);
        controls.add(zoomOut);
        controls.add(resetZoom);
        controls.add(zoomIn);
        controls.add(status);

        JPanel relationshipControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        relationshipControls.add(new JLabel(AstLensBundle.message("relationship.search.label")));
        relationshipControls.add(relationshipSelector);
        relationshipControls.add(searchRelationship);

        JPanel header = new JPanel(new BorderLayout());
        header.add(controls, BorderLayout.NORTH);
        header.add(relationshipControls, BorderLayout.SOUTH);

        add(header, BorderLayout.NORTH);
        add(graphContainer, BorderLayout.CENTER);

        installEditorListeners();
    }

    private static <T> T readPhase(
            ProgressIndicator indicator,
            double fraction,
            String messageKey,
            Supplier<T> operation
    ) {
        updateProgress(indicator, fraction, messageKey);
        T result = ReadAction.computeBlocking(() -> {
            ProgressManager.checkCanceled();
            return operation.get();
        });
        ProgressManager.checkCanceled();
        return result;
    }

    private static void updateProgress(ProgressIndicator indicator, double fraction, String messageKey) {
        ProgressManager.checkCanceled();
        indicator.setIndeterminate(false);
        indicator.setFraction(fraction);
        indicator.setText(AstLensBundle.message(messageKey));
    }

    private static String cardFor(int view) {
        return switch (view) {
            case CALL_GRAPH_VIEW -> CALL_GRAPH_CARD;
            case CLASS_DEPENDENCIES_VIEW -> CLASS_DEPENDENCIES_CARD;
            case PACKAGE_DEPENDENCIES_VIEW -> PACKAGE_DEPENDENCIES_CARD;
            case PSI_UAST_VIEW -> PSI_UAST_CARD;
            case RELATIONSHIP_VIEW -> RELATIONSHIP_CARD;
            case CODE_HEALTH_VIEW -> CODE_HEALTH_CARD;
            case PROJECT_STRUCTURE_VIEW -> PROJECT_STRUCTURE_CARD;
            case PROJECT_CALL_GRAPH_VIEW -> PROJECT_CALL_GRAPH_CARD;
            default -> STRUCTURE_CARD;
        };
    }

    public void analyzeCurrentFile() {
        if (DumbService.isDumb(project)) {
            status.setText(AstLensBundle.message("status.indexing"));
            DumbService.getInstance(project).runWhenSmart(this::analyzeCurrentFile);
            return;
        }

        VirtualFile[] selected = FileEditorManager.getInstance(project).getSelectedFiles();
        VirtualFile file = selected.length == 0 ? null : selected[0];
        if (file == null) {
            Messages.showInfoMessage(
                    project,
                    AstLensBundle.message("dialog.open.file"),
                    AstLensBundle.message("dialog.title")
            );
            return;
        }

        PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
        if (psiFile == null) {
            showUnsupportedFile();
            return;
        }

        status.setText(AstLensBundle.message("status.analyzing", file.getName()));
        new Task.Backgroundable(
                project,
                AstLensBundle.message("progress.file.title", file.getName()),
                true
        ) {
            private PanelAnalysis result;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                AstAnalysisResult structure = readPhase(
                        indicator,
                        0.05,
                        "progress.phase.structure",
                        () -> analyzer.analyze(psiFile)
                );
                if (structure == null) {
                    return;
                }
                CallGraph calls = readPhase(
                        indicator,
                        0.20,
                        "progress.phase.calls",
                        () -> callGraphAnalyzer.analyze(psiFile)
                );
                DependencyAnalysis dependencies = readPhase(
                        indicator,
                        0.38,
                        "progress.phase.dependencies",
                        () -> dependencyGraphAnalyzer.analyze(project)
                );
                SyntaxComparison comparison = readPhase(
                        indicator,
                        0.58,
                        "progress.phase.comparison",
                        () -> syntaxComparisonAnalyzer.analyze(psiFile)
                );
                CodeHealthReport health = readPhase(
                        indicator,
                        0.72,
                        "progress.phase.health",
                        () -> codeHealthAnalyzer.analyze(project, dependencies)
                );

                updateProgress(indicator, 0.84, "progress.phase.layout");
                result = new PanelAnalysis(
                        structure,
                        calls,
                        dependencies,
                        comparison,
                        health,
                        AstGraphLayout.calculate(structure.root()),
                        CallGraphLayout.calculate(calls),
                        CallGraphLayout.calculate(dependencies.classGraph()),
                        CallGraphLayout.calculate(dependencies.packageGraph())
                );
                updateProgress(indicator, 1.0, "progress.phase.ready");
            }

            @Override
            public void onSuccess() {
                showResult(result);
            }

            @Override
            public void onCancel() {
                showCancelled();
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                showOperationError("file", file.getName(), error);
            }
        }.queue();
    }

    private void analyzeProject() {
        if (DumbService.isDumb(project)) {
            status.setText(AstLensBundle.message("status.indexing"));
            DumbService.getInstance(project).runWhenSmart(this::analyzeProject);
            return;
        }

        status.setText(AstLensBundle.message("status.analyzing.project"));
        new Task.Backgroundable(
                project,
                AstLensBundle.message("progress.project.title"),
                true
        ) {
            private ProjectPanelAnalysis result;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                ProjectAnalysis projectAnalysis = readPhase(
                        indicator,
                        0.05,
                        "progress.phase.project",
                        () -> projectAnalyzer.analyze(project)
                );
                DependencyAnalysis dependencies = readPhase(
                        indicator,
                        0.48,
                        "progress.phase.dependencies",
                        () -> dependencyGraphAnalyzer.analyze(project)
                );
                CodeHealthReport health = readPhase(
                        indicator,
                        0.68,
                        "progress.phase.health",
                        () -> codeHealthAnalyzer.analyze(project, dependencies)
                );

                updateProgress(indicator, 0.80, "progress.phase.layout");
                result = new ProjectPanelAnalysis(
                        projectAnalysis,
                        dependencies,
                        health,
                        CallGraphLayout.calculate(projectAnalysis.structureGraph()),
                        CallGraphLayout.calculate(projectAnalysis.callGraph()),
                        CallGraphLayout.calculate(dependencies.classGraph()),
                        CallGraphLayout.calculate(dependencies.packageGraph())
                );
                updateProgress(indicator, 1.0, "progress.phase.ready");
            }

            @Override
            public void onSuccess() {
                showProjectResult(result);
            }

            @Override
            public void onCancel() {
                showCancelled();
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                showOperationError("project", project.getName(), error);
            }
        }.queue();
    }

    private void showProjectResult(ProjectPanelAnalysis result) {
        currentProjectAnalysis = result.projectAnalysis();
        projectStructureGraph.setLayout(result.projectStructureLayout());
        projectCallGraph.setLayout(result.projectCallLayout());
        currentDependencies = result.dependencies();
        classDependencyGraph.setLayout(result.classDependencyLayout());
        packageDependencyGraph.setLayout(result.packageDependencyLayout());
        currentCodeHealth = result.codeHealth();
        codeHealthPanel.setReport(currentCodeHealth);
        exportButton.setEnabled(true);
        viewSelector.setSelectedIndex(PROJECT_STRUCTURE_VIEW);
        String truncation = currentProjectAnalysis.truncated()
                ? AstLensBundle.message("status.project.truncated")
                : "";
        String cacheSummary = AstLensBundle.message(
                "status.project.cache",
                currentProjectAnalysis.reanalyzedFileCount(),
                currentProjectAnalysis.reusedFileCount(),
                currentProjectAnalysis.removedFileCount()
        );
        status.setText(AstLensBundle.message(
                "status.project.result",
                currentProjectAnalysis.fileCount(),
                currentProjectAnalysis.packageCount(),
                currentProjectAnalysis.classCount(),
                currentProjectAnalysis.methodCount(),
                truncation,
                cacheSummary
        ));
    }

    private void exportCurrentView() {
        if (!hasDataForActiveView()) {
            Messages.showInfoMessage(
                    project,
                    AstLensBundle.message("export.no.data"),
                    AstLensBundle.message("export.title")
            );
            return;
        }

        GraphExporter.Format[] formats = GraphExporter.Format.values();
        String[] options = java.util.Arrays.stream(formats)
                .map(GraphExporter.Format::displayName)
                .toArray(String[]::new);
        int selected = Messages.showDialog(
                project,
                AstLensBundle.message("export.choose.format"),
                AstLensBundle.message("export.title"),
                options,
                0,
                Messages.getQuestionIcon()
        );
        if (selected < 0) {
            return;
        }

        GraphExporter.Format format = formats[selected];
        ExportPayload payload = createExportPayload(format);
        FileSaverDescriptor descriptor = new FileSaverDescriptor(
                AstLensBundle.message("export.save.title"),
                AstLensBundle.message("export.save.description"),
                format.extension()
        );
        VirtualFileWrapper destination = FileChooserFactory.getInstance()
                .createSaveFileDialog(descriptor, project)
                .save(
                        ProjectUtil.guessProjectDir(project),
                        payload.fileName() + "." + format.extension()
                );
        if (destination == null) {
            return;
        }

        try {
            Files.writeString(destination.getFile().toPath(), payload.content(), StandardCharsets.UTF_8);
            status.setText(AstLensBundle.message("export.success", destination.getFile().getName()));
        } catch (IOException exception) {
            Messages.showErrorDialog(
                    project,
                    AstLensBundle.message("export.error", exception.getMessage()),
                    AstLensBundle.message("export.title")
            );
        }
    }

    private boolean hasDataForActiveView() {
        return switch (activeView) {
            case STRUCTURE_VIEW, CALL_GRAPH_VIEW, PSI_UAST_VIEW -> currentAnalysis != null
                    && analyzedFile != null;
            case CLASS_DEPENDENCIES_VIEW, PACKAGE_DEPENDENCIES_VIEW -> currentDependencies != null;
            case RELATIONSHIP_VIEW -> currentRelationship != null;
            case CODE_HEALTH_VIEW -> currentCodeHealth != null;
            case PROJECT_STRUCTURE_VIEW, PROJECT_CALL_GRAPH_VIEW -> currentProjectAnalysis != null;
            default -> false;
        };
    }

    private ExportPayload createExportPayload(GraphExporter.Format format) {
        String fileBaseName = analyzedFile == null
                ? project.getName()
                : analyzedFile.getName().replaceFirst("\\.[^.]+$", "");
        return switch (activeView) {
            case CALL_GRAPH_VIEW -> new ExportPayload(
                    fileBaseName + "-call-graph",
                    GraphExporter.exportDirectedGraph(
                            currentAnalysis.callGraph(),
                            AstLensBundle.message("view.call.graph"),
                            format
                    )
            );
            case CLASS_DEPENDENCIES_VIEW -> new ExportPayload(
                    project.getName() + "-class-dependencies",
                    GraphExporter.exportDirectedGraph(
                            currentDependencies.classGraph(),
                            AstLensBundle.message("view.class.dependencies"),
                            format
                    )
            );
            case PACKAGE_DEPENDENCIES_VIEW -> new ExportPayload(
                    project.getName() + "-package-dependencies",
                    GraphExporter.exportDirectedGraph(
                            currentDependencies.packageGraph(),
                            AstLensBundle.message("view.package.dependencies"),
                            format
                    )
            );
            case PSI_UAST_VIEW -> new ExportPayload(
                    fileBaseName + "-psi-uast",
                    GraphExporter.exportComparison(currentAnalysis.syntaxComparison(), format)
            );
            case RELATIONSHIP_VIEW -> new ExportPayload(
                    fileBaseName + "-" + currentRelationshipQuery.name().toLowerCase(java.util.Locale.ROOT),
                    GraphExporter.exportDirectedGraph(
                            currentRelationship.graph(),
                            AstLensBundle.message(currentRelationshipQuery.messageKey()),
                            format
                    )
            );
            case CODE_HEALTH_VIEW -> new ExportPayload(
                    project.getName() + "-code-health",
                    GraphExporter.exportCodeHealth(currentCodeHealth, format)
            );
            case PROJECT_STRUCTURE_VIEW -> new ExportPayload(
                    project.getName() + "-project-structure",
                    GraphExporter.exportDirectedGraph(
                            currentProjectAnalysis.structureGraph(),
                            AstLensBundle.message("view.project.structure"),
                            format
                    )
            );
            case PROJECT_CALL_GRAPH_VIEW -> new ExportPayload(
                    project.getName() + "-project-call-graph",
                    GraphExporter.exportDirectedGraph(
                            currentProjectAnalysis.callGraph(),
                            AstLensBundle.message("view.project.call.graph"),
                            format
                    )
            );
            default -> new ExportPayload(
                    fileBaseName + "-structure",
                    GraphExporter.exportStructure(currentAnalysis.structure().root(), format)
            );
        };
    }

    private void showResult(PanelAnalysis result) {
        if (result == null) {
            showUnsupportedFile();
            return;
        }
        VirtualFile[] selected = FileEditorManager.getInstance(project).getSelectedFiles();
        if (selected.length == 0 || !result.structure().file().equals(selected[0])) {
            return;
        }
        analyzedFile = result.structure().file();
        currentAnalysis = result;
        structureGraph.setLayout(result.structureLayout());
        callGraph.setLayout(result.callLayout());
        currentDependencies = result.dependencies();
        classDependencyGraph.setLayout(result.classDependencyLayout());
        packageDependencyGraph.setLayout(result.packageDependencyLayout());
        syntaxComparisonPanel.setComparison(result.syntaxComparison());
        currentCodeHealth = result.codeHealth();
        codeHealthPanel.setReport(currentCodeHealth);
        clearRelationshipResult();
        exportButton.setEnabled(true);
        synchronizeWithCurrentCaret();
        status.setText(AstLensBundle.message("status.result", analyzedFile.getName()));
    }

    private void showUnsupportedFile() {
        analyzedFile = null;
        currentAnalysis = null;
        structureGraph.setLayout(AstGraphLayout.empty());
        callGraph.setLayout(CallGraphLayout.empty());
        syntaxComparisonPanel.setComparison(null);
        clearRelationshipResult();
        if (currentProjectAnalysis == null) {
            classDependencyGraph.setLayout(CallGraphLayout.empty());
            packageDependencyGraph.setLayout(CallGraphLayout.empty());
            codeHealthPanel.setReport(null);
            currentDependencies = null;
            currentCodeHealth = null;
        }
        exportButton.setEnabled(currentProjectAnalysis != null);
        status.setText(AstLensBundle.message("status.unsupported"));
    }

    private void searchRelationship() {
        if (DumbService.isDumb(project)) {
            status.setText(AstLensBundle.message("status.indexing"));
            DumbService.getInstance(project).runWhenSmart(this::searchRelationship);
            return;
        }

        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        VirtualFile file = editor == null
                ? null
                : FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (editor == null || file == null || currentAnalysis == null || !file.equals(analyzedFile)) {
            Messages.showInfoMessage(
                    project,
                    AstLensBundle.message("relationship.analyze.first"),
                    AstLensBundle.message("dialog.title")
            );
            return;
        }

        PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
        if (psiFile == null) {
            showUnsupportedFile();
            return;
        }
        RelationshipQuery query = RelationshipQuery.values()[relationshipSelector.getSelectedIndex()];
        RelationshipRequest request = new RelationshipRequest(
                file,
                editor.getCaretModel().getOffset(),
                query
        );
        DependencyAnalysis dependencies = currentAnalysis.dependencies();
        status.setText(AstLensBundle.message("relationship.searching"));
        new Task.Backgroundable(
                project,
                AstLensBundle.message("progress.relationship.title"),
                true
        ) {
            private RelationshipResponse response;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                RelationshipResult result = readPhase(
                        indicator,
                        0.10,
                        "progress.phase.relationship",
                        () -> relationshipAnalyzer.analyze(
                                project,
                                psiFile,
                                request.offset(),
                                request.query(),
                                dependencies
                        )
                );
                updateProgress(indicator, 0.80, "progress.phase.layout");
                response = new RelationshipResponse(
                        request,
                        result,
                        result == null
                                ? CallGraphLayout.empty()
                                : CallGraphLayout.calculate(result.graph())
                );
                updateProgress(indicator, 1.0, "progress.phase.ready");
            }

            @Override
            public void onSuccess() {
                showRelationshipResult(response);
            }

            @Override
            public void onCancel() {
                showCancelled();
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                showOperationError(
                        "relationship",
                        AstLensBundle.message(request.query().messageKey()),
                        error
                );
            }
        }.queue();
    }

    private void navigateTo(AstNode node) {
        if (analyzedFile != null && node.startOffset() >= 0) {
            new OpenFileDescriptor(project, analyzedFile, node.startOffset()).navigate(true);
        }
    }

    private void navigateToCall(CallGraphNode node) {
        if (node.file() != null && node.startOffset() >= 0) {
            new OpenFileDescriptor(project, node.file(), node.startOffset()).navigate(true);
        }
    }

    private void navigateToSyntaxNode(SyntaxTreeNode node) {
        if (analyzedFile != null && node.startOffset() >= 0) {
            new OpenFileDescriptor(project, analyzedFile, node.startOffset()).navigate(true);
        }
    }

    private void navigateToHealthIssue(CodeHealthIssue issue) {
        if (issue.file() != null && issue.startOffset() >= 0) {
            new OpenFileDescriptor(project, issue.file(), issue.startOffset()).navigate(true);
        }
    }

    private void zoomIn() {
        switch (activeView) {
            case CALL_GRAPH_VIEW -> callGraph.zoomIn();
            case CLASS_DEPENDENCIES_VIEW -> classDependencyGraph.zoomIn();
            case PACKAGE_DEPENDENCIES_VIEW -> packageDependencyGraph.zoomIn();
            case RELATIONSHIP_VIEW -> relationshipGraph.zoomIn();
            case PROJECT_STRUCTURE_VIEW -> projectStructureGraph.zoomIn();
            case PROJECT_CALL_GRAPH_VIEW -> projectCallGraph.zoomIn();
            case PSI_UAST_VIEW, CODE_HEALTH_VIEW -> {
            }
            default -> structureGraph.zoomIn();
        }
    }

    private void zoomOut() {
        switch (activeView) {
            case CALL_GRAPH_VIEW -> callGraph.zoomOut();
            case CLASS_DEPENDENCIES_VIEW -> classDependencyGraph.zoomOut();
            case PACKAGE_DEPENDENCIES_VIEW -> packageDependencyGraph.zoomOut();
            case RELATIONSHIP_VIEW -> relationshipGraph.zoomOut();
            case PROJECT_STRUCTURE_VIEW -> projectStructureGraph.zoomOut();
            case PROJECT_CALL_GRAPH_VIEW -> projectCallGraph.zoomOut();
            case PSI_UAST_VIEW, CODE_HEALTH_VIEW -> {
            }
            default -> structureGraph.zoomOut();
        }
    }

    private void resetZoom() {
        switch (activeView) {
            case CALL_GRAPH_VIEW -> callGraph.resetZoom();
            case CLASS_DEPENDENCIES_VIEW -> classDependencyGraph.resetZoom();
            case PACKAGE_DEPENDENCIES_VIEW -> packageDependencyGraph.resetZoom();
            case RELATIONSHIP_VIEW -> relationshipGraph.resetZoom();
            case PROJECT_STRUCTURE_VIEW -> projectStructureGraph.resetZoom();
            case PROJECT_CALL_GRAPH_VIEW -> projectCallGraph.resetZoom();
            case PSI_UAST_VIEW, CODE_HEALTH_VIEW -> {
            }
            default -> structureGraph.resetZoom();
        }
    }

    private void showRelationshipResult(RelationshipResponse response) {
        VirtualFile[] selected = FileEditorManager.getInstance(project).getSelectedFiles();
        if (selected.length == 0 || !response.request().file().equals(selected[0])) {
            return;
        }
        RelationshipResult result = response.result();
        if (result == null) {
            String key = response.request().query().methodRequired()
                    ? "relationship.target.method.required"
                    : "relationship.target.class.required";
            Messages.showInfoMessage(
                    project,
                    AstLensBundle.message(key),
                    AstLensBundle.message("dialog.title")
            );
            status.setText(AstLensBundle.message("relationship.no.target"));
            return;
        }

        currentRelationship = result;
        currentRelationshipQuery = response.request().query();
        relationshipGraph.setLayout(response.layout());
        relationshipGraph.selectNodeById(result.targetId());
        viewSelector.setSelectedIndex(RELATIONSHIP_VIEW);
        status.setText(AstLensBundle.message(
                "relationship.result",
                result.relationCount(),
                result.targetLabel()
        ));
    }

    private void synchronizeWithCurrentCaret() {
        synchronizeWithCaret(FileEditorManager.getInstance(project).getSelectedTextEditor());
    }

    private void synchronizeWithCaret(Editor editor) {
        if (editor == null || editor.getProject() != project) {
            return;
        }
        VirtualFile editorFile = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (editorFile == null) {
            return;
        }
        int offset = editor.getCaretModel().getOffset();
        projectStructureGraph.selectNodeAtOffset(editorFile, offset);
        projectCallGraph.selectNodeAtOffset(editorFile, offset);
        if (analyzedFile != null && analyzedFile.equals(editorFile)) {
            structureGraph.selectNodeAtOffset(offset);
            callGraph.selectNodeAtOffset(editorFile, offset);
            classDependencyGraph.selectNodeAtOffset(editorFile, offset);
            if (currentDependencies != null) {
                packageDependencyGraph.selectNodeById(currentDependencies.packageIdByFile().get(editorFile));
            }
            syntaxComparisonPanel.selectOffset(offset);
            relationshipGraph.selectNodeAtOffset(editorFile, offset);
        }
    }

    private void clearRelationshipResult() {
        currentRelationship = null;
        currentRelationshipQuery = null;
        relationshipGraph.setLayout(CallGraphLayout.empty());
    }

    private void installEditorListeners() {
        EditorFactory.getInstance().getEventMulticaster().addCaretListener(new CaretListener() {
            @Override
            public void caretPositionChanged(@NotNull CaretEvent event) {
                synchronizeWithCaret(event.getEditor());
            }
        }, this);

        project.getMessageBus().connect(this).subscribe(
                FileEditorManagerListener.FILE_EDITOR_MANAGER,
                new FileEditorManagerListener() {
                    @Override
                    public void selectionChanged(@NotNull FileEditorManagerEvent event) {
                        VirtualFile file = event.getNewFile();
                        if (file == null) {
                            analyzedFile = null;
                            currentAnalysis = null;
                            structureGraph.setLayout(AstGraphLayout.empty());
                            callGraph.setLayout(CallGraphLayout.empty());
                            syntaxComparisonPanel.setComparison(null);
                            clearRelationshipResult();
                            if (currentProjectAnalysis == null) {
                                classDependencyGraph.setLayout(CallGraphLayout.empty());
                                packageDependencyGraph.setLayout(CallGraphLayout.empty());
                                codeHealthPanel.setReport(null);
                                currentDependencies = null;
                                currentCodeHealth = null;
                            }
                            exportButton.setEnabled(currentProjectAnalysis != null);
                            status.setText(AstLensBundle.message("status.open.file"));
                        } else if (!file.equals(analyzedFile)) {
                            analyzeCurrentFile();
                        } else {
                            synchronizeWithCurrentCaret();
                        }
                    }
                }
        );
    }

    private void showCancelled() {
        status.setText(AstLensBundle.message("status.cancelled"));
    }

    private void showOperationError(String operation, String subject, Throwable error) {
        LOG.warn("AST Lens " + operation + " operation failed for " + subject, error);
        String detail = error.getMessage();
        if (detail == null || detail.isBlank()) {
            detail = error.getClass().getSimpleName();
        }
        String messageKey = switch (operation) {
            case "project" -> "error.analysis.project";
            case "relationship" -> "error.analysis.relationship";
            default -> "error.analysis.file";
        };
        status.setText(AstLensBundle.message("status.failed", subject));
        Messages.showErrorDialog(
                project,
                AstLensBundle.message(messageKey, subject, detail),
                AstLensBundle.message("dialog.title")
        );
    }

    @Override
    public void dispose() {
        // Les connexions et listeners sont automatiquement retirés par le Disposable parent.
    }

    private record PanelAnalysis(
            AstAnalysisResult structure,
            CallGraph callGraph,
            DependencyAnalysis dependencies,
            SyntaxComparison syntaxComparison,
            CodeHealthReport codeHealth,
            AstGraphLayout structureLayout,
            CallGraphLayout callLayout,
            CallGraphLayout classDependencyLayout,
            CallGraphLayout packageDependencyLayout
    ) {
    }

    private record ProjectPanelAnalysis(
            ProjectAnalysis projectAnalysis,
            DependencyAnalysis dependencies,
            CodeHealthReport codeHealth,
            CallGraphLayout projectStructureLayout,
            CallGraphLayout projectCallLayout,
            CallGraphLayout classDependencyLayout,
            CallGraphLayout packageDependencyLayout
    ) {
    }

    private record ExportPayload(String fileName, String content) {
    }

    private record RelationshipRequest(VirtualFile file, int offset, RelationshipQuery query) {
    }

    private record RelationshipResponse(
            RelationshipRequest request,
            RelationshipResult result,
            CallGraphLayout layout
    ) {
    }
}
