package io.github.mercurievv.purrism.ideaplugin

import com.intellij.openapi.actionSystem.{AnAction, AnActionEvent}
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.jcef.{JBCefApp, JBCefBrowser}
import org.cef.browser.CefBrowser
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import java.nio.file.{Files, Path, StandardCopyOption}
import javax.swing.{JButton, JComponent, JPanel, JTextField}

private val LOG = Logger.getInstance("Purrism")

/** Only traces JCEF wiring under `-Didea.is.internal=true` (set by
  * `ijPlugin.runIde`) -- noise-free for a normal install.
  */
private def debugLog(message: => String): Unit =
  if (ApplicationManager.getApplication.isInternal) LOG.info(message)
private def debugWarn(message: => String): Unit =
  if (ApplicationManager.getApplication.isInternal) LOG.warn(message)

/** Extracts the bundled web/ resources (html + three.min.js) to a temp dir once
  * per IDE session, since JCEF cannot load them straight out of the plugin
  * jar's classpath. Shared by both the Three.js cube viewer and the module
  * dependency graph viewer -- not file-private since [[PurrismGraphAction]] (a
  * different source file) also uses it.
  */
object ViewerAssets {
  private lazy val dir: Path = {
    val tmp = Files.createTempDirectory("purrism-viewer")
    for (
      name <- Seq("purrism-viewer.html", "purrism-graph.html", "three.min.js")
    ) {
      val in = classOf[PurrismThreeJsAction].getResourceAsStream(s"/web/$name")
      try Files.copy(in, tmp.resolve(name), StandardCopyOption.REPLACE_EXISTING)
      finally in.close()
    }
    tmp
  }

  def indexUrl: String = dir.resolve("purrism-viewer.html").toUri.toString
  def graphIndexUrl: String = dir.resolve("purrism-graph.html").toUri.toString
}

class PurrismViewerDialog(project: Project)
    extends DialogWrapper(project, false) {
  private val browser = new JBCefBrowser(ViewerAssets.indexUrl)
  private val input = new JTextField(30)
  private val runButton = new JButton("Send")

  runButton.addActionListener { _ =>
    onSend(input.getText)
  }

  browser.getJBCefClient.addLoadHandler(
    new CefLoadHandlerAdapter {
      override def onLoadEnd(
          cefBrowser: CefBrowser,
          frame: org.cef.browser.CefFrame,
          httpStatusCode: Int
      ): Unit =
        debugLog(
          s"[purrism-jcef] load finished url=${cefBrowser.getURL} httpStatusCode=$httpStatusCode"
        )

      override def onLoadError(
          cefBrowser: CefBrowser,
          frame: org.cef.browser.CefFrame,
          errorCode: org.cef.handler.CefLoadHandler.ErrorCode,
          errorText: String,
          failedUrl: String
      ): Unit =
        debugWarn(
          s"[purrism-jcef] load error url=$failedUrl code=$errorCode text=$errorText"
        )
    },
    browser.getCefBrowser
  )

  setTitle("Purrism Viewer")
  init()

  /** Scala-side callback for the button; currently pushes the text into the
    * running Three.js scene and echoes it back into the field as feedback.
    */
  private def onSend(text: String): Unit = {
    debugLog(s"[purrism-jcef] Send clicked, text=$text")
    val escaped = text.replace("\\", "\\\\").replace("\"", "\\\"")
    browser.getCefBrowser.executeJavaScript(
      s"""window.purrismSetLabel && window.purrismSetLabel("$escaped");""",
      browser.getCefBrowser.getURL,
      0
    )
  }

  override def createCenterPanel(): JComponent = {
    val panel = new JPanel(new BorderLayout())
    panel.setPreferredSize(new java.awt.Dimension(800, 600))
    panel.add(browser.getComponent, BorderLayout.CENTER)

    val controls = new JPanel()
    controls.add(input)
    controls.add(runButton)
    panel.add(controls, BorderLayout.SOUTH)
    panel
  }
}

class PurrismThreeJsAction extends AnAction {
  override def actionPerformed(e: AnActionEvent): Unit = {
    val project = e.getProject
    if (project == null) return
    debugLog(s"[purrism-jcef] JBCefApp.isSupported=${JBCefApp.isSupported}")
    if (!JBCefApp.isSupported) {
      com.intellij.openapi.ui.Messages.showErrorDialog(
        project,
        "JCEF is not supported in this IDE runtime.",
        "Purrism Viewer"
      )
      return
    }
    new PurrismViewerDialog(project).show()
  }
}
