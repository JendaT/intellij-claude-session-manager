package com.github.jendalen.claudesessionmanager.settings

import com.github.jendalen.claudesessionmanager.services.ProjectSettingsService
import com.github.jendalen.claudesessionmanager.util.LaunchArgsValidator
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/**
 * Project-scoped settings page ("Launch Profile") nested under the application page.
 */
class ProjectSettingsConfigurable(private val project: Project) : Configurable {

    private var panel: DialogPanel? = null
    private val settings get() = ProjectSettingsService.getInstance(project)

    private var extraLaunchArgs = settings.extraLaunchArgs

    override fun getDisplayName(): String = "Launch Profile"

    override fun createComponent(): JComponent {
        panel = panel {
            group("Extra Launch Arguments (this project)") {
                row("Arguments:") {
                    textField()
                        .bindText(::extraLaunchArgs)
                        .columns(60)
                        .comment(
                            "Inserted after the Claude command on every launch in this project: " +
                                "<code>claude &lt;arguments&gt; --resume &lt;id&gt;</code>. " +
                                "Example: <code>--channels plugin:name@marketplace</code>. " +
                                "Letters, digits and <code>_ . : = / , @ -</code> only; session selection flags are not allowed."
                        )
                }
            }
        }
        return panel!!
    }

    override fun isModified(): Boolean {
        return panel?.isModified() == true || extraLaunchArgs != settings.extraLaunchArgs
    }

    override fun apply() {
        // Transfer the UI value to the bound property first - without this the field
        // appears to save but reverts on the next open (documented trap in this codebase).
        panel?.apply()
        val reason = LaunchArgsValidator.rejectionReason(extraLaunchArgs)
        if (reason != null) {
            throw ConfigurationException(reason)
        }
        settings.extraLaunchArgs = extraLaunchArgs
    }

    override fun reset() {
        extraLaunchArgs = settings.extraLaunchArgs
        panel?.reset()
    }

    override fun disposeUIResources() {
        panel = null
    }
}
