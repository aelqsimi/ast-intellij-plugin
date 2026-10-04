package com.aelqsimi.ast.analysis;

import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;

public enum AnalysisScope {
    PROJECT_ONLY,
    PROJECT_AND_DEPENDENCIES;

    public boolean includes(ProjectFileIndex fileIndex, PsiElement element) {
        if (element == null) {
            return false;
        }
        PsiElement navigation = element.getNavigationElement();
        PsiFile containingFile = navigation == null ? null : navigation.getContainingFile();
        return includes(fileIndex, containingFile == null ? null : containingFile.getVirtualFile());
    }

    public boolean includes(ProjectFileIndex fileIndex, VirtualFile file) {
        if (file == null || !file.isValid()) {
            return false;
        }
        if (fileIndex.isInContent(file)) {
            return true;
        }
        return this == PROJECT_AND_DEPENDENCIES
                && fileIndex.isInLibrary(file)
                && !fileIndex.findContainingLibraries(file).isEmpty()
                && fileIndex.findContainingSdks(file).isEmpty();
    }
}
