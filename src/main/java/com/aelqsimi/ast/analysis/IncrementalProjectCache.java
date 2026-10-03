package com.aelqsimi.ast.analysis;

import com.aelqsimi.ast.AstLensBundle;
import com.aelqsimi.ast.model.CallGraph;
import com.aelqsimi.ast.model.CallGraphEdge;
import com.aelqsimi.ast.model.CallGraphNode;
import com.aelqsimi.ast.model.CodeHealthIssue;
import com.aelqsimi.ast.model.DependencyAnalysis;
import com.aelqsimi.ast.model.ProjectAnalysis;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootModificationTracker;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.util.PsiTypesUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.uast.UBinaryExpression;
import org.jetbrains.uast.UCallExpression;
import org.jetbrains.uast.UCatchClause;
import org.jetbrains.uast.UClass;
import org.jetbrains.uast.UElement;
import org.jetbrains.uast.UFile;
import org.jetbrains.uast.UIfExpression;
import org.jetbrains.uast.ULoopExpression;
import org.jetbrains.uast.UMethod;
import org.jetbrains.uast.USimpleNameReferenceExpression;
import org.jetbrains.uast.USwitchClauseExpressionWithBody;
import org.jetbrains.uast.UTypeReferenceExpression;
import org.jetbrains.uast.UastBinaryOperator;
import org.jetbrains.uast.UastContextKt;
import org.jetbrains.uast.visitor.AbstractUastVisitor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service(Service.Level.PROJECT)
public final class IncrementalProjectCache {
    public static final int CLASS_LINE_THRESHOLD = 500;
    public static final int METHOD_COMPLEXITY_THRESHOLD = 10;
    private static final int MAX_NODES_PER_GRAPH = 750;

    private final Project project;
    private final Map<VirtualFile, CachedFile> files = new LinkedHashMap<>();
    private long rootModificationCount = -1;
    private CoreSnapshot aggregated;

    public IncrementalProjectCache(Project project) {
        this.project = project;
    }

    public static IncrementalProjectCache getInstance(Project project) {
        return project.getService(IncrementalProjectCache.class);
    }

    public synchronized Snapshot snapshot() {
        ProgressManager.checkCanceled();
        long currentRootCount = ProjectRootModificationTracker.getInstance(project).getModificationCount();
        int removedFiles = 0;
        if (rootModificationCount != currentRootCount) {
            removedFiles = files.size();
            files.clear();
            aggregated = null;
            rootModificationCount = currentRootCount;
        }

        Set<VirtualFile> sourceFiles = sourceFiles(project);
        PsiManager psiManager = PsiManager.getInstance(project);
        Map<VirtualFile, PsiFile> psiFiles = new LinkedHashMap<>();
        for (VirtualFile file : sourceFiles) {
            PsiFile psiFile = psiManager.findFile(file);
            if (psiFile != null) {
                psiFiles.put(file, psiFile);
            }
        }

        Set<VirtualFile> deleted = new LinkedHashSet<>(files.keySet());
        deleted.removeAll(psiFiles.keySet());
        List<FileFacts> deletedFacts = deleted.stream()
                .map(files::get)
                .filter(java.util.Objects::nonNull)
                .map(CachedFile::facts)
                .toList();

        Set<VirtualFile> directlyChanged = new LinkedHashSet<>();
        for (Map.Entry<VirtualFile, PsiFile> entry : psiFiles.entrySet()) {
            VirtualFile file = entry.getKey();
            CachedFile cached = files.get(file);
            if (cached == null
                    || cached.modificationStamp() != entry.getValue().getModificationStamp()
                    || !cached.path().equals(file.getPath())) {
                directlyChanged.add(file);
            }
        }

        Set<String> changedClassIds = new LinkedHashSet<>();
        Set<String> changedMethodIds = new LinkedHashSet<>();
        directlyChanged.stream()
                .map(files::get)
                .filter(java.util.Objects::nonNull)
                .map(CachedFile::facts)
                .forEach(facts -> collectDeclarations(facts, changedClassIds, changedMethodIds));
        deletedFacts.forEach(facts -> collectDeclarations(facts, changedClassIds, changedMethodIds));

        Set<VirtualFile> affected = new LinkedHashSet<>(directlyChanged);
        if (!directlyChanged.isEmpty() || !deleted.isEmpty()) {
            for (Map.Entry<VirtualFile, CachedFile> entry : files.entrySet()) {
                if (!deleted.contains(entry.getKey())
                        && (dependsOn(entry.getValue().facts(), changedClassIds, changedMethodIds)
                        || hasUnresolvedReferences(entry.getValue().facts()))) {
                    affected.add(entry.getKey());
                }
            }
        }

        removedFiles += deleted.size();

        int reanalyzedFiles = 0;
        int reusedFiles = 0;
        boolean changed = !deleted.isEmpty();
        ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);
        Map<VirtualFile, CachedFile> nextFiles = new LinkedHashMap<>(files);
        deleted.forEach(nextFiles::remove);
        for (Map.Entry<VirtualFile, PsiFile> entry : psiFiles.entrySet()) {
            ProgressManager.checkCanceled();
            VirtualFile file = entry.getKey();
            PsiFile psiFile = entry.getValue();
            if (!affected.contains(file)) {
                reusedFiles++;
                continue;
            }
            nextFiles.put(file, new CachedFile(
                    psiFile.getModificationStamp(),
                    file.getPath(),
                    analyzeFile(psiFile, fileIndex)
            ));
            reanalyzedFiles++;
            changed = true;
        }

        if (aggregated == null || changed) {
            CoreSnapshot nextAggregated = aggregate(
                    nextFiles.values().stream().map(CachedFile::facts).toList()
            );
            files.clear();
            files.putAll(nextFiles);
            aggregated = nextAggregated;
        }
        ProjectAnalysis projectAnalysis = aggregated.projectAnalysis(reanalyzedFiles, reusedFiles, removedFiles);
        return new Snapshot(
                projectAnalysis,
                aggregated.completeCallGraph(),
                aggregated.dependencies(),
                aggregated.localHealthIssues()
        );
    }

    private static void collectDeclarations(
            FileFacts facts,
            Set<String> classIds,
            Set<String> methodIds
    ) {
        facts.classes().values().stream()
                .filter(classFact -> classFact.kind() == CallGraphNode.Kind.INTERNAL)
                .filter(classFact -> facts.file() != null && facts.file().equals(classFact.file()))
                .map(ClassFact::id)
                .forEach(classIds::add);
        facts.callNodes().values().stream()
                .filter(node -> node.kind() == CallGraphNode.Kind.INTERNAL)
                .filter(node -> facts.file() != null && facts.file().equals(node.file()))
                .map(CallGraphNode::id)
                .forEach(methodIds::add);
    }

    private static boolean dependsOn(
            FileFacts facts,
            Set<String> changedClassIds,
            Set<String> changedMethodIds
    ) {
        return facts.classEdges().keySet().stream()
                .anyMatch(edge -> changedClassIds.contains(edge.targetId()))
                || facts.callEdges().keySet().stream()
                .anyMatch(edge -> changedMethodIds.contains(edge.targetId()));
    }

    private static boolean hasUnresolvedReferences(FileFacts facts) {
        return facts.hasUnresolvedReferences();
    }

    private static Set<VirtualFile> sourceFiles(Project project) {
        Set<VirtualFile> sourceFiles = new LinkedHashSet<>();
        ProjectFileIndex.getInstance(project).iterateContent(file -> {
            if (!file.isDirectory() && isJvmSource(file)) {
                sourceFiles.add(file);
            }
            return true;
        });
        return sourceFiles;
    }

    private static FileFacts analyzeFile(PsiFile psiFile, ProjectFileIndex fileIndex) {
        VirtualFile file = psiFile.getVirtualFile();
        UFile uFile = file == null ? null : UastContextKt.toUElement(psiFile, UFile.class);
        if (uFile == null || file == null) {
            return FileFacts.empty();
        }

        String packageName = uFile.getPackageName().isBlank() ? "<default>" : uFile.getPackageName();
        MutableFileFacts facts = new MutableFileFacts(file, packageName);
        facts.addFileStructure(psiFile.getTextLength());
        Deque<String> structuralParents = new ArrayDeque<>();
        Deque<String> currentMethods = new ArrayDeque<>();
        Deque<String> currentClasses = new ArrayDeque<>();
        structuralParents.push(facts.fileId);

        uFile.accept(new AbstractUastVisitor() {
            @Override
            public boolean visitClass(@NotNull UClass uClass) {
                ProgressManager.checkCanceled();
                PsiClass psiClass = uClass.getJavaPsi();
                SourceRange range = sourceRange(uClass.getSourcePsi());
                ClassFact classFact = classFact(psiClass, fileIndex).asInternal(file, range);
                facts.mergeClass(classFact);
                facts.classCount++;
                facts.firstClassId = facts.firstClassId == null ? classFact.id() : facts.firstClassId;
                facts.addStructureNode(
                        "class:" + classFact.id(),
                        classFact.label(),
                        file,
                        range,
                        structuralParents.peek()
                );
                structuralParents.push("class:" + classFact.id());
                currentClasses.push(classFact.id());

                PsiElement source = uClass.getSourcePsi();
                if (source != null) {
                    int lines = lineCount(source.getText());
                    if (lines > CLASS_LINE_THRESHOLD) {
                        facts.healthIssues.add(healthIssue(
                                CodeHealthIssue.Kind.LARGE_CLASS,
                                classFact.id(),
                                lines,
                                CLASS_LINE_THRESHOLD,
                                source
                        ));
                    }
                }
                return false;
            }

            @Override
            public void afterVisitClass(@NotNull UClass uClass) {
                structuralParents.pop();
                currentClasses.pop();
            }

            @Override
            public boolean visitMethod(@NotNull UMethod method) {
                ProgressManager.checkCanceled();
                PsiMethod psiMethod = method.getJavaPsi();
                SourceRange range = sourceRange(method.getSourcePsi());
                String id = methodId(psiMethod);
                String label = methodLabel(psiMethod);
                facts.methodCount++;
                facts.addStructureNode("method:" + id, label, file, range, structuralParents.peek());
                structuralParents.push("method:" + id);
                facts.mergeCallNode(new CallGraphNode(
                        id,
                        label,
                        CallGraphNode.Kind.INTERNAL,
                        file,
                        range.startOffset(),
                        range.endOffset()
                ));
                currentMethods.push(id);

                PsiElement source = method.getSourcePsi();
                if (source != null && method.getUastBody() != null) {
                    int complexity = complexity(method);
                    if (complexity > METHOD_COMPLEXITY_THRESHOLD) {
                        facts.healthIssues.add(healthIssue(
                                CodeHealthIssue.Kind.COMPLEX_METHOD,
                                methodId(psiMethod),
                                complexity,
                                METHOD_COMPLEXITY_THRESHOLD,
                                source
                        ));
                    }
                }
                return false;
            }

            @Override
            public void afterVisitMethod(@NotNull UMethod method) {
                structuralParents.pop();
                currentMethods.pop();
            }

            @Override
            public boolean visitCallExpression(@NotNull UCallExpression call) {
                PsiMethod resolved = call.resolve();
                if (!currentMethods.isEmpty()) {
                    CallGraphNode target = resolved == null
                            ? unresolvedCallNode(call)
                            : methodNode(resolved, fileIndex);
                    facts.mergeCallNode(target);
                    facts.callEdges.merge(
                            new EdgeKey(currentMethods.peek(), target.id()),
                            1,
                            Integer::sum
                    );
                }
                if (resolved == null) {
                    facts.hasUnresolvedReferences = true;
                }
                if (resolved != null) {
                    facts.addDependency(currentClasses, resolved.getContainingClass(), fileIndex);
                }
                return false;
            }

            @Override
            public boolean visitTypeReferenceExpression(@NotNull UTypeReferenceExpression reference) {
                PsiClass target = PsiTypesUtil.getPsiClass(reference.getType());
                if (target == null) {
                    facts.hasUnresolvedReferences = true;
                }
                facts.addDependency(currentClasses, target, fileIndex);
                return false;
            }

            @Override
            public boolean visitSimpleNameReferenceExpression(
                    @NotNull USimpleNameReferenceExpression reference
            ) {
                if (reference.getUastParent() instanceof UCallExpression) {
                    return false;
                }
                PsiElement resolved = reference.resolve();
                if (resolved == null) {
                    facts.hasUnresolvedReferences = true;
                }
                PsiClass target = resolved instanceof PsiClass psiClass
                        ? psiClass
                        : resolved instanceof PsiMember member ? member.getContainingClass() : null;
                facts.addDependency(currentClasses, target, fileIndex);
                return false;
            }
        });
        return facts.freeze();
    }

    private static CoreSnapshot aggregate(List<FileFacts> fileFacts) {
        LimitedGraph structure = new LimitedGraph(MAX_NODES_PER_GRAPH);
        LimitedGraph calls = new LimitedGraph(MAX_NODES_PER_GRAPH);
        Map<String, CallGraphNode> completeCallNodes = new LinkedHashMap<>();
        Map<EdgeKey, Integer> completeCallEdges = new LinkedHashMap<>();
        Map<String, ClassFact> classes = new LinkedHashMap<>();
        Map<EdgeKey, Integer> classEdges = new LinkedHashMap<>();
        Map<VirtualFile, String> classIdByFile = new LinkedHashMap<>();
        Map<VirtualFile, String> packageIdByFile = new LinkedHashMap<>();
        List<CodeHealthIssue> healthIssues = new ArrayList<>();
        Set<String> packages = new LinkedHashSet<>();
        int classCount = 0;
        int methodCount = 0;

        for (FileFacts facts : fileFacts) {
            facts.structureNodes().values().forEach(structure::addNode);
            facts.structureEdges().forEach((edge, count) -> structure.addEdge(edge, count));
            facts.callNodes().values().forEach(calls::addNode);
            facts.callEdges().forEach((edge, count) -> calls.addEdge(edge, count));
            facts.callNodes().values().forEach(node -> mergeGraphNode(completeCallNodes, node));
            facts.callEdges().forEach((edge, count) ->
                    completeCallEdges.merge(edge, count, Integer::sum));
            facts.classes().values().forEach(classFact -> mergeClass(classes, classFact));
            facts.classEdges().forEach((edge, count) -> classEdges.merge(edge, count, Integer::sum));
            if (facts.file() != null) {
                if (facts.firstClassId() != null) {
                    classIdByFile.put(facts.file(), facts.firstClassId());
                }
                packageIdByFile.put(facts.file(), facts.packageName());
            }
            packages.add(facts.packageName());
            healthIssues.addAll(facts.healthIssues());
            classCount += facts.classCount();
            methodCount += facts.methodCount();
        }

        CallGraph classGraph = new CallGraph(
                classes.values().stream().map(ClassFact::node).toList(),
                freezeEdges(classEdges)
        );
        CallGraph packageGraph = packageGraph(classes, classEdges);
        DependencyAnalysis dependencies = new DependencyAnalysis(
                classGraph,
                packageGraph,
                classIdByFile,
                packageIdByFile
        );
        return new CoreSnapshot(
                structure.freeze(),
                calls.freeze(),
                new CallGraph(
                        completeCallNodes.values().stream().toList(),
                        freezeEdges(completeCallEdges)
                ),
                fileFacts.size(),
                packages.size(),
                classCount,
                methodCount,
                structure.truncated() || calls.truncated(),
                dependencies,
                List.copyOf(healthIssues)
        );
    }

    private static CallGraph packageGraph(
            Map<String, ClassFact> classes,
            Map<EdgeKey, Integer> classEdges
    ) {
        Map<String, ClassFact> packages = new LinkedHashMap<>();
        Map<EdgeKey, Integer> edges = new LinkedHashMap<>();
        for (Map.Entry<EdgeKey, Integer> entry : classEdges.entrySet()) {
            ClassFact source = classes.get(entry.getKey().sourceId());
            ClassFact target = classes.get(entry.getKey().targetId());
            if (source == null || target == null || source.packageName().equals(target.packageName())) {
                continue;
            }
            mergePackage(packages, source);
            mergePackage(packages, target);
            edges.merge(
                    new EdgeKey(source.packageName(), target.packageName()),
                    entry.getValue(),
                    Integer::sum
            );
        }
        return new CallGraph(
                packages.values().stream().map(ClassFact::node).toList(),
                freezeEdges(edges)
        );
    }

    private static void mergePackage(Map<String, ClassFact> packages, ClassFact classFact) {
        ClassFact candidate = classFact.asPackage();
        ClassFact current = packages.get(candidate.id());
        if (current == null || candidate.kind() == CallGraphNode.Kind.INTERNAL
                && current.kind() != CallGraphNode.Kind.INTERNAL) {
            packages.put(candidate.id(), candidate);
        }
    }

    private static List<CallGraphEdge> freezeEdges(Map<EdgeKey, Integer> edges) {
        return edges.entrySet().stream()
                .map(entry -> new CallGraphEdge(
                        entry.getKey().sourceId(),
                        entry.getKey().targetId(),
                        entry.getValue()
                ))
                .toList();
    }

    private static void mergeClass(Map<String, ClassFact> classes, ClassFact candidate) {
        ClassFact current = classes.get(candidate.id());
        if (current == null || candidate.kind() == CallGraphNode.Kind.INTERNAL
                && current.kind() != CallGraphNode.Kind.INTERNAL) {
            classes.put(candidate.id(), candidate);
        }
    }

    private static void mergeGraphNode(Map<String, CallGraphNode> nodes, CallGraphNode candidate) {
        CallGraphNode current = nodes.get(candidate.id());
        if (current == null || candidate.kind() == CallGraphNode.Kind.INTERNAL
                && current.kind() != CallGraphNode.Kind.INTERNAL) {
            nodes.put(candidate.id(), candidate);
        }
    }

    private static int complexity(UMethod method) {
        int[] value = {1};
        method.getUastBody().accept(new AbstractUastVisitor() {
            @Override
            public boolean visitElement(@NotNull UElement node) {
                if (node instanceof UIfExpression
                        || node instanceof ULoopExpression
                        || node instanceof USwitchClauseExpressionWithBody
                        || node instanceof UCatchClause) {
                    value[0]++;
                } else if (node instanceof UBinaryExpression binary
                        && (binary.getOperator() == UastBinaryOperator.LOGICAL_AND
                        || binary.getOperator() == UastBinaryOperator.LOGICAL_OR)) {
                    value[0]++;
                }
                return false;
            }
        });
        return value[0];
    }

    private static CodeHealthIssue healthIssue(
            CodeHealthIssue.Kind kind,
            String symbol,
            int value,
            int threshold,
            PsiElement source
    ) {
        SourceLocation location = sourceLocation(source);
        return new CodeHealthIssue(
                kind,
                symbol,
                value,
                threshold,
                List.of(),
                location.file(),
                location.startOffset(),
                location.endOffset()
        );
    }

    private static CallGraphNode unresolvedCallNode(UCallExpression call) {
        String methodName = call.getMethodName();
        String label = methodName == null || methodName.isBlank()
                ? "<" + AstLensBundle.message("node.unnamed.call") + ">"
                : methodName + "()";
        return new CallGraphNode(
                "unresolved:" + label,
                label,
                CallGraphNode.Kind.UNRESOLVED,
                null,
                -1,
                -1
        );
    }

    private static CallGraphNode methodNode(PsiMethod method, ProjectFileIndex fileIndex) {
        PsiElement navigation = method.getNavigationElement();
        SourceLocation location = sourceLocation(navigation);
        CallGraphNode.Kind kind = location.file() != null && fileIndex.isInContent(location.file())
                ? CallGraphNode.Kind.INTERNAL
                : CallGraphNode.Kind.EXTERNAL;
        return new CallGraphNode(
                methodId(method),
                methodLabel(method),
                kind,
                location.file(),
                location.startOffset(),
                location.endOffset()
        );
    }

    private static ClassFact classFact(PsiClass psiClass, ProjectFileIndex fileIndex) {
        String qualifiedName = psiClass.getQualifiedName();
        String name = psiClass.getName() == null ? "<anonymous>" : psiClass.getName();
        PsiElement navigation = psiClass.getNavigationElement();
        SourceLocation location = sourceLocation(navigation);
        String id = qualifiedName != null
                ? qualifiedName
                : (location.file() == null ? "<unknown>" : location.file().getPath()) + "#" + name;
        CallGraphNode.Kind kind = location.file() != null && fileIndex.isInContent(location.file())
                ? CallGraphNode.Kind.INTERNAL
                : CallGraphNode.Kind.EXTERNAL;
        return new ClassFact(
                id,
                name,
                packageName(psiClass),
                kind,
                location.file(),
                location.startOffset(),
                location.endOffset()
        );
    }

    private static String methodId(PsiMethod method) {
        PsiClass owner = method.getContainingClass();
        String ownerName = owner == null
                ? "<top-level>"
                : owner.getQualifiedName() == null ? owner.getName() : owner.getQualifiedName();
        String parameters = Arrays.stream(method.getParameterList().getParameters())
                .map(PsiParameter::getType)
                .map(type -> type.getCanonicalText(false))
                .collect(Collectors.joining(","));
        return ownerName + "#" + method.getName() + "(" + parameters + ")";
    }

    private static String methodLabel(PsiMethod method) {
        PsiClass owner = method.getContainingClass();
        String ownerName = owner == null || owner.getName() == null ? "" : owner.getName() + ".";
        return ownerName + method.getName() + "()";
    }

    private static String packageName(PsiClass psiClass) {
        PsiClass topLevel = psiClass;
        while (topLevel.getContainingClass() != null) {
            topLevel = topLevel.getContainingClass();
        }
        String qualifiedName = topLevel.getQualifiedName();
        if (qualifiedName == null || !qualifiedName.contains(".")) {
            return "<default>";
        }
        return qualifiedName.substring(0, qualifiedName.lastIndexOf('.'));
    }

    private static int lineCount(String text) {
        if (text.isEmpty()) {
            return 0;
        }
        int lines = 1;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    private static SourceRange sourceRange(PsiElement element) {
        if (element == null || element.getTextRange() == null) {
            return new SourceRange(-1, -1);
        }
        TextRange range = element.getTextRange();
        return new SourceRange(range.getStartOffset(), range.getEndOffset());
    }

    private static SourceLocation sourceLocation(PsiElement element) {
        if (element == null) {
            return new SourceLocation(null, -1, -1);
        }
        PsiElement navigation = element.getNavigationElement();
        PsiFile containingFile = navigation.getContainingFile();
        VirtualFile file = containingFile == null ? null : containingFile.getVirtualFile();
        TextRange range = navigation.getTextRange();
        return range == null
                ? new SourceLocation(file, -1, -1)
                : new SourceLocation(file, range.getStartOffset(), range.getEndOffset());
    }

    private static boolean isJvmSource(VirtualFile file) {
        String extension = file.getExtension();
        return "java".equalsIgnoreCase(extension) || "kt".equalsIgnoreCase(extension);
    }

    public record Snapshot(
            ProjectAnalysis projectAnalysis,
            CallGraph completeCallGraph,
            DependencyAnalysis dependencies,
            List<CodeHealthIssue> localHealthIssues
    ) {
        public Snapshot {
            localHealthIssues = List.copyOf(localHealthIssues);
        }
    }

    private record CachedFile(long modificationStamp, String path, FileFacts facts) {
    }

    private record SourceRange(int startOffset, int endOffset) {
    }

    private record SourceLocation(VirtualFile file, int startOffset, int endOffset) {
    }

    private record EdgeKey(String sourceId, String targetId) {
    }

    private record ClassFact(
            String id,
            String label,
            String packageName,
            CallGraphNode.Kind kind,
            VirtualFile file,
            int startOffset,
            int endOffset
    ) {
        private ClassFact asInternal(VirtualFile sourceFile, SourceRange range) {
            return new ClassFact(
                    id,
                    label,
                    packageName,
                    CallGraphNode.Kind.INTERNAL,
                    sourceFile,
                    range.startOffset(),
                    range.endOffset()
            );
        }

        private ClassFact asPackage() {
            return new ClassFact(
                    packageName,
                    packageName,
                    packageName,
                    kind,
                    file,
                    startOffset,
                    endOffset
            );
        }

        private CallGraphNode node() {
            return new CallGraphNode(id, label, kind, file, startOffset, endOffset);
        }
    }

    private record FileFacts(
            VirtualFile file,
            String packageName,
            Map<String, CallGraphNode> structureNodes,
            Map<EdgeKey, Integer> structureEdges,
            Map<String, CallGraphNode> callNodes,
            Map<EdgeKey, Integer> callEdges,
            Map<String, ClassFact> classes,
            Map<EdgeKey, Integer> classEdges,
            String firstClassId,
            List<CodeHealthIssue> healthIssues,
            int classCount,
            int methodCount,
            boolean hasUnresolvedReferences
    ) {
        private FileFacts {
            structureNodes = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(structureNodes));
            structureEdges = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(structureEdges));
            callNodes = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(callNodes));
            callEdges = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(callEdges));
            classes = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(classes));
            classEdges = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(classEdges));
            healthIssues = List.copyOf(healthIssues);
        }

        private static FileFacts empty() {
            return new FileFacts(
                    null,
                    "<default>",
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    null,
                    List.of(),
                    0,
                    0,
                    false
            );
        }
    }

    private static final class MutableFileFacts {
        private final VirtualFile file;
        private final String packageName;
        private final String packageId;
        private final String fileId;
        private final Map<String, CallGraphNode> structureNodes = new LinkedHashMap<>();
        private final Map<EdgeKey, Integer> structureEdges = new LinkedHashMap<>();
        private final Map<String, CallGraphNode> callNodes = new LinkedHashMap<>();
        private final Map<EdgeKey, Integer> callEdges = new LinkedHashMap<>();
        private final Map<String, ClassFact> classes = new LinkedHashMap<>();
        private final Map<EdgeKey, Integer> classEdges = new LinkedHashMap<>();
        private final List<CodeHealthIssue> healthIssues = new ArrayList<>();
        private String firstClassId;
        private int classCount;
        private int methodCount;
        private boolean hasUnresolvedReferences;

        private MutableFileFacts(VirtualFile file, String packageName) {
            this.file = file;
            this.packageName = packageName;
            packageId = "package:" + packageName;
            fileId = "file:" + file.getPath();
        }

        private void addFileStructure(int textLength) {
            structureNodes.put(packageId, new CallGraphNode(
                    packageId,
                    packageName,
                    CallGraphNode.Kind.INTERNAL,
                    file,
                    0,
                    0
            ));
            structureNodes.put(fileId, new CallGraphNode(
                    fileId,
                    file.getName(),
                    CallGraphNode.Kind.INTERNAL,
                    file,
                    0,
                    textLength
            ));
            structureEdges.put(new EdgeKey(packageId, fileId), 1);
        }

        private void addStructureNode(
                String id,
                String label,
                VirtualFile sourceFile,
                SourceRange range,
                String parentId
        ) {
            structureNodes.put(id, new CallGraphNode(
                    id,
                    label,
                    CallGraphNode.Kind.INTERNAL,
                    sourceFile,
                    range.startOffset(),
                    range.endOffset()
            ));
            structureEdges.merge(new EdgeKey(parentId, id), 1, Integer::sum);
        }

        private void mergeCallNode(CallGraphNode candidate) {
            CallGraphNode current = callNodes.get(candidate.id());
            if (current == null || candidate.kind() == CallGraphNode.Kind.INTERNAL
                    && current.kind() != CallGraphNode.Kind.INTERNAL) {
                callNodes.put(candidate.id(), candidate);
            }
        }

        private void mergeClass(ClassFact candidate) {
            IncrementalProjectCache.mergeClass(classes, candidate);
        }

        private void addDependency(
                Deque<String> currentClasses,
                PsiClass targetClass,
                ProjectFileIndex fileIndex
        ) {
            if (currentClasses.isEmpty() || targetClass == null) {
                return;
            }
            ClassFact target = classFact(targetClass, fileIndex);
            String sourceId = currentClasses.peek();
            if (sourceId.equals(target.id())) {
                return;
            }
            mergeClass(target);
            classEdges.merge(new EdgeKey(sourceId, target.id()), 1, Integer::sum);
        }

        private FileFacts freeze() {
            return new FileFacts(
                    file,
                    packageName,
                    structureNodes,
                    structureEdges,
                    callNodes,
                    callEdges,
                    classes,
                    classEdges,
                    firstClassId,
                    healthIssues,
                    classCount,
                    methodCount,
                    hasUnresolvedReferences
            );
        }
    }

    private record CoreSnapshot(
            CallGraph structureGraph,
            CallGraph callGraph,
            CallGraph completeCallGraph,
            int fileCount,
            int packageCount,
            int classCount,
            int methodCount,
            boolean truncated,
            DependencyAnalysis dependencies,
            List<CodeHealthIssue> localHealthIssues
    ) {
        private ProjectAnalysis projectAnalysis(int reanalyzed, int reused, int removed) {
            return new ProjectAnalysis(
                    structureGraph,
                    callGraph,
                    fileCount,
                    packageCount,
                    classCount,
                    methodCount,
                    truncated,
                    reanalyzed,
                    reused,
                    removed
            );
        }
    }

    private static final class LimitedGraph {
        private final int limit;
        private final Map<String, CallGraphNode> nodes = new LinkedHashMap<>();
        private final Map<EdgeKey, Integer> edges = new LinkedHashMap<>();
        private boolean truncated;

        private LimitedGraph(int limit) {
            this.limit = limit;
        }

        private void addNode(CallGraphNode node) {
            CallGraphNode existing = nodes.get(node.id());
            if (existing != null) {
                if (node.kind() == CallGraphNode.Kind.INTERNAL
                        && existing.kind() != CallGraphNode.Kind.INTERNAL) {
                    nodes.put(node.id(), node);
                }
                return;
            }
            if (nodes.size() >= limit) {
                truncated = true;
                return;
            }
            nodes.put(node.id(), node);
        }

        private void addEdge(EdgeKey edge, int count) {
            if (nodes.containsKey(edge.sourceId()) && nodes.containsKey(edge.targetId())) {
                edges.merge(edge, count, Integer::sum);
            } else {
                truncated = true;
            }
        }

        private boolean truncated() {
            return truncated;
        }

        private CallGraph freeze() {
            return new CallGraph(nodes.values().stream().toList(), freezeEdges(edges));
        }
    }
}
