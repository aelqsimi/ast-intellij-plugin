package com.aelqsimi.ast.ui;

import com.aelqsimi.ast.AstLensBundle;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public final class AstLensToolWindowFactory implements ToolWindowFactory, DumbAware {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        AstLensPanel panel = new AstLensPanel(project);
        Content content = ContentFactory.getInstance().createContent(
                panel,
                AstLensBundle.message("toolwindow.tab.diagram"),
                false
        );
        content.setPreferredFocusableComponent(panel);
        content.setDisposer(panel);
        toolWindow.getContentManager().addContent(content);
    }
}
