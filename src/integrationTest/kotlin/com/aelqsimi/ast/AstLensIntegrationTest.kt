package com.aelqsimi.ast

import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.shouldBe
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.ci.CIServer
import com.intellij.ide.starter.ci.NoCIServer
import com.intellij.ide.starter.di.di
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IDETestContext
import com.intellij.ide.starter.models.IdeInfo
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.project.NoProject
import com.intellij.ide.starter.runner.Starter
import com.intellij.platform.testFramework.teamCity.TeamCityReporter.SyntheticTestKind
import com.intellij.tools.ide.starter.product.idea.ultimate.IdeaUltimate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.time.Duration.Companion.minutes

class AstLensIntegrationTest {
    companion object {
        init {
            di = DI {
                extend(di)
                bindSingleton<CIServer>(overrides = true) {
                    object : CIServer by NoCIServer {
                        override fun reportTestFailure(
                            testName: String,
                            message: String,
                            details: String,
                            linkToLogs: String?,
                            kind: SyntheticTestKind,
                            generifyTestName: Boolean,
                        ) {
                            fail("$testName fails: $message.\n$details\n${linkToLogs.orEmpty()}")
                        }
                    }
                }
            }
        }
    }

    private val pluginPath = Path(System.getProperty("path.to.build.plugin"))

    @Test
    fun ideStartsWithAstLensInstalled() {
        Starter.newContext(
            "ast-lens-plugin-startup",
            TestCase(IdeInfo.IdeaUltimate, NoProject).withVersion("2026.2.3"),
        ).apply {
            useStableTestTheme()
            PluginConfigurator(this).installPluginFromPath(pluginPath)
        }.runIdeWithDriver().useDriverAndCloseIde { }
    }

    @Test
    fun opensAstLensToolWindowForJavaFile() {
        val projectPath = Path.of(System.getProperty("ast.lens.integration.project"))
        Starter.newContext(
            "ast-lens-open-tool-window",
            TestCase(IdeInfo.IdeaUltimate, LocalProjectInfo(projectPath)).withVersion("2026.2.3"),
        ).apply {
            useStableTestTheme()
            PluginConfigurator(this).installPluginFromPath(pluginPath)
        }.runIdeWithDriver().useDriverAndCloseIde {
            waitForIndicators(5.minutes)
            openFile("src/main/java/demo/JavaSample.java")
            ideFrame {
                invokeAction("com.aelqsimi.ast.OpenAstLensAction", now = false)
                shouldBe("AST Lens tool window content is visible") {
                    x { byAccessibleName("AST Lens content") }.present()
                }
            }
        }
    }

    private fun IDETestContext.useStableTestTheme() {
        val optionsDirectory = paths.configDir.resolve("options")
        Files.createDirectories(optionsDirectory)
        Files.writeString(
            optionsDirectory.resolve("laf.xml"),
            """
            <application>
              <component name="LafManager" autodetect="false">
                <laf class-name="com.intellij.ide.ui.laf.darcula.DarculaLaf" themeId="Islands Darcula" />
              </component>
            </application>
            """.trimIndent(),
        )
        Files.writeString(
            optionsDirectory.resolve("colors.scheme.xml"),
            """
            <application>
              <component name="EditorColorsManagerImpl">
                <global_color_scheme name="Darcula" />
              </component>
            </application>
            """.trimIndent(),
        )
    }
}
