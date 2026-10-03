package com.aelqsimi.ast.analysis;

import com.aelqsimi.ast.model.ProjectAnalysis;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Coarse-grained performance checks that exercise real PSI and UAST conversion.
 * Generous configurable budgets catch severe regressions, while the CSV report
 * preserves the raw measurements for comparisons between revisions.
 */
public final class AstLensPerformanceTest extends BasePlatformTestCase {
    private static final int[] PROJECT_SIZES = {10, 50, 100};
    private static final int METHODS_PER_FILE = 8;

    private static final long MAX_COLD_MS = budget("maxColdMs", 60_000);
    private static final long MAX_WARM_MS = budget("maxWarmMs", 5_000);
    private static final long MAX_EDIT_MS = budget("maxEditMs", 15_000);

    public void testCacheAtDifferentProjectSizesAndAfterOneEdit() throws IOException {
        List<PsiFile> files = new ArrayList<>();
        List<Measurement> measurements = new ArrayList<>();
        IncrementalProjectCache largestCache = null;

        for (int projectSize : PROJECT_SIZES) {
            addFilesUntil(files, projectSize);
            IncrementalProjectCache coldCache = new IncrementalProjectCache(getProject());
            Measurement cold = measure("cold-" + projectSize, coldCache);
            measurements.add(cold);

            assertEquals("The cold analysis must include every generated source file",
                    projectSize, cold.analysis().fileCount());
            assertEquals("A new cache must analyze every source file",
                    projectSize, cold.analysis().reanalyzedFileCount());
            assertWithinBudget(cold, MAX_COLD_MS);
            largestCache = coldCache;
        }

        assertNotNull(largestCache);

        Measurement warm = measure("warm-" + files.size(), largestCache);
        measurements.add(warm);
        assertEquals("A warm snapshot must not reanalyze unchanged files",
                0, warm.analysis().reanalyzedFileCount());
        assertEquals("A warm snapshot must reuse every generated file",
                files.size(), warm.analysis().reusedFileCount());
        assertWithinBudget(warm, MAX_WARM_MS);

        appendMethod(files.getFirst());
        Measurement edited = measure("single-edit-" + files.size(), largestCache);
        measurements.add(edited);
        assertEquals("An independent edit must reanalyze only its source file",
                1, edited.analysis().reanalyzedFileCount());
        assertEquals("Every other source file must remain cached after an independent edit",
                files.size() - 1, edited.analysis().reusedFileCount());
        assertWithinBudget(edited, MAX_EDIT_MS);

        writeReport(measurements);
    }

    private void addFilesUntil(List<PsiFile> files, int targetSize) {
        while (files.size() < targetSize) {
            int index = files.size();
            boolean kotlin = index % 5 == 4;
            String extension = kotlin ? "kt" : "java";
            String className = (kotlin ? "PerfKotlin" : "PerfJava")
                    + String.format(Locale.ROOT, "%03d", index);
            files.add(myFixture.addFileToProject(
                    "src/perf/" + className + "." + extension,
                    kotlin ? kotlinSource(className) : javaSource(className)
            ));
        }
    }

    private static String javaSource(String className) {
        StringBuilder source = new StringBuilder("package perf;\nfinal class ")
                .append(className)
                .append(" {\n");
        for (int method = 0; method < METHODS_PER_FILE; method++) {
            source.append("    int method")
                    .append(method)
                    .append("() { return ")
                    .append(method)
                    .append("; }\n");
        }
        return source.append("}\n").toString();
    }

    private static String kotlinSource(String className) {
        StringBuilder source = new StringBuilder("package perf\nclass ")
                .append(className)
                .append(" {\n");
        for (int method = 0; method < METHODS_PER_FILE; method++) {
            source.append("    fun method")
                    .append(method)
                    .append("(): Int = ")
                    .append(method)
                    .append('\n');
        }
        return source.append("}\n").toString();
    }

    private void appendMethod(PsiFile file) {
        Document document = PsiDocumentManager.getInstance(getProject()).getDocument(file);
        assertNotNull(document);
        int classEnd = document.getText().lastIndexOf('}');
        assertTrue(classEnd >= 0);
        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            document.insertString(classEnd, "    int addedAfterWarmup() { return 42; }\n");
            PsiDocumentManager.getInstance(getProject()).commitDocument(document);
        });
    }

    private Measurement measure(String scenario, IncrementalProjectCache cache) {
        long heapBefore = usedHeapBytes();
        long started = System.nanoTime();
        ProjectAnalysis analysis = ReadAction.computeBlocking(
                () -> cache.snapshot().projectAnalysis()
        );
        long durationNanos = System.nanoTime() - started;
        long heapAfter = usedHeapBytes();
        Measurement measurement = new Measurement(
                scenario,
                analysis,
                durationNanos,
                heapBefore,
                heapAfter
        );
        System.out.printf(
                Locale.ROOT,
                "AST_LENS_PERF scenario=%s files=%d durationMs=%.3f heapDeltaBytes=%d reanalyzed=%d reused=%d%n",
                scenario,
                analysis.fileCount(),
                measurement.durationMillis(),
                measurement.heapDeltaBytes(),
                analysis.reanalyzedFileCount(),
                analysis.reusedFileCount()
        );
        return measurement;
    }

    private static void assertWithinBudget(Measurement measurement, long budgetMs) {
        assertTrue(
                measurement.scenario() + " took " + measurement.durationMillis()
                        + " ms; budget is " + budgetMs + " ms",
                measurement.durationMs() <= budgetMs
        );
    }

    private static long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static long budget(String suffix, long defaultValue) {
        return Long.getLong("ast.lens.performance." + suffix, defaultValue);
    }

    private static void writeReport(List<Measurement> measurements) throws IOException {
        Path reportDirectory = Path.of(System.getProperty(
                "ast.lens.performance.reportDir",
                "build/reports/ast-lens-performance"
        ));
        Files.createDirectories(reportDirectory);
        StringBuilder csv = new StringBuilder(
                "scenario,fileCount,durationMs,heapBeforeBytes,heapAfterBytes,heapDeltaBytes,"
                        + "reanalyzedFiles,reusedFiles,removedFiles\n"
        );
        for (Measurement measurement : measurements) {
            ProjectAnalysis analysis = measurement.analysis();
            csv.append(measurement.scenario()).append(',')
                    .append(analysis.fileCount()).append(',')
                    .append(String.format(Locale.ROOT, "%.3f", measurement.durationMillis())).append(',')
                    .append(measurement.heapBeforeBytes()).append(',')
                    .append(measurement.heapAfterBytes()).append(',')
                    .append(measurement.heapDeltaBytes()).append(',')
                    .append(analysis.reanalyzedFileCount()).append(',')
                    .append(analysis.reusedFileCount()).append(',')
                    .append(analysis.removedFileCount()).append('\n');
        }
        Files.writeString(
                reportDirectory.resolve("performance-results.csv"),
                csv,
                StandardCharsets.UTF_8
        );
    }

    private record Measurement(
            String scenario,
            ProjectAnalysis analysis,
            long durationNanos,
            long heapBeforeBytes,
            long heapAfterBytes
    ) {
        private long durationMs() {
            return durationNanos / 1_000_000;
        }

        private double durationMillis() {
            return durationNanos / 1_000_000.0;
        }

        private long heapDeltaBytes() {
            return heapAfterBytes - heapBeforeBytes;
        }
    }
}
