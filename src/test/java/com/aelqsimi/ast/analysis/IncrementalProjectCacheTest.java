package com.aelqsimi.ast.analysis;

import com.aelqsimi.ast.model.ProjectAnalysis;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public final class IncrementalProjectCacheTest extends BasePlatformTestCase {
    public void testUnchangedFilesAreReusedAndModifiedFileIsReanalyzed() {
        PsiFile file = myFixture.addFileToProject(
                "src/demo/Sample.java",
                """
                        package demo;
                        class Sample {
                            void first() {}
                        }
                        """
        );

        ProjectAnalysis first = snapshot();
        ProjectAnalysis second = snapshot();

        assertTrue(first.reanalyzedFileCount() >= 1);
        assertEquals(0, second.reanalyzedFileCount());
        assertTrue(second.reusedFileCount() >= 1);

        Document document = PsiDocumentManager.getInstance(getProject()).getDocument(file);
        assertNotNull(document);
        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            document.insertString(document.getTextLength() - 2, "\n    void second() {}\n");
            PsiDocumentManager.getInstance(getProject()).commitDocument(document);
        });

        ProjectAnalysis afterEdit = snapshot();

        assertTrue(afterEdit.reanalyzedFileCount() >= 1);
        assertTrue(afterEdit.methodCount() >= 2);
    }

    private ProjectAnalysis snapshot() {
        return ReadAction.computeBlocking(() ->
                IncrementalProjectCache.getInstance(getProject()).snapshot().projectAnalysis());
    }
}
