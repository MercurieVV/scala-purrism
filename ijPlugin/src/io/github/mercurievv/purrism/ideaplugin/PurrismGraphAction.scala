package io.github.mercurievv.purrism.ideaplugin

import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex
import com.intellij.openapi.actionSystem.{AnAction, AnActionEvent}
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.{ProgressIndicator, ProgressManager, Task}
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.{DialogWrapper, Messages}
import com.intellij.ui.jcef.{JBCefApp, JBCefBrowser}
import io.github.mercurievv.purrism.graphmodel.{
  EdgeKind,
  GraphJson,
  GraphModelBuilder,
  GraphPayload,
  ModuleProjection,
  NodeKind
}
import org.cef.browser.CefBrowser
import org.cef.handler.CefLoadHandlerAdapter

import java.io.IOException
import java.awt.BorderLayout
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{FileVisitResult, Files, Path, Paths, SimpleFileVisitor}
import javax.swing.JComponent
import scala.jdk.CollectionConverters.*

/** Opens the Three.js module-dependency graph: scans the project for
  * `META-INF/semanticdb` payloads, builds the full entity graph via
  * [[GraphModelBuilder]], collapses it to modules via [[ModuleProjection]] (the
  * raw entity graph -- classes, methods, values -- is deliberately not sent to
  * the UI; only the module-level summary is), and pushes the JSON into the
  * viewer once its page has loaded.
  */
class PurrismGraphAction extends AnAction {
  override def actionPerformed(e: AnActionEvent): Unit = {
    LOG.info("[purrism-graph] action triggered")
    val project = e.getProject
    if (project == null) {
      LOG.warn("[purrism-graph] no project in action event, aborting")
      return
    }
    if (!JBCefApp.isSupported) {
      LOG.warn("[purrism-graph] JBCefApp.isSupported=false")
      Messages.showErrorDialog(
        project,
        "JCEF is not supported in this IDE runtime.",
        "Purrism Module Graph"
      )
      return
    }

    val dialog = new PurrismGraphDialog(project)
    dialog.show()

    ProgressManager
      .getInstance()
      .run(new Task.Backgroundable(project, "Building Purrism module graph") {
        override def run(indicator: ProgressIndicator): Unit = {
          LOG.info("[purrism-graph] background task started")
          try {
            indicator.setText("Scanning for SemanticDB payloads...")
            val roots =
              findSemanticdbClasspathRoots(Paths.get(project.getBasePath))
            LOG.info(
              s"[purrism-graph] found ${roots.size} semanticdb roots: ${roots.mkString(", ")}"
            )
            val index = SemanticIndex.fromRoots(roots)
            LOG.info(
              s"[purrism-graph] indexed ${index.documents.size} semanticdb documents"
            )
            indicator.setText("Building graph model...")
            val full = GraphModelBuilder.build(index)
            LOG.info(
              s"[purrism-graph] full graph: ${full.nodes.size} nodes, ${full.edges.size} edges"
            )
            val moduleGraph = ModuleProjection.project(full)
            LOG.info(
              s"[purrism-graph] module graph: ${moduleGraph.nodes.size} nodes, ${moduleGraph.edges.size} edges"
            )
            val keptModules = moduleGraph.nodes.map(_.name).toSet
            val classNodes = full.nodes.filter(n =>
              (n.kind == NodeKind.ClassDef || n.kind == NodeKind.TraitDef ||
                n.kind == NodeKind.ObjectDef) && keptModules.contains(n.module)
            )
            LOG.info(
              s"[purrism-graph] ${classNodes.size} class/trait/object nodes across ${keptModules.size} modules"
            )
            val classNodeIds = classNodes.map(_.id).toSet
            val classEdges = full.edges.filter(e =>
              e.kind != EdgeKind.Contains && classNodeIds.contains(
                e.from
              ) && classNodeIds.contains(e.to)
            )
            LOG.info(
              s"[purrism-graph] ${classEdges.size} dependency edges among class/trait/object nodes"
            )
            val fileNodes = full.nodes.filter(n =>
              n.kind == NodeKind.File && keptModules.contains(n.module)
            )
            val fileNodeIds = fileNodes.map(_.id).toSet
            val fileContainsEdges = full.edges.filter(e =>
              e.kind == EdgeKind.Contains && fileNodeIds.contains(
                e.from
              ) && classNodeIds.contains(e.to)
            )
            LOG.info(
              s"[purrism-graph] ${fileNodes.size} files across ${keptModules.size} modules, ${fileContainsEdges.size} file->class contains edges"
            )
            val json =
              GraphJson.toJson(
                GraphPayload(
                  moduleGraph,
                  fileNodes,
                  fileContainsEdges,
                  classNodes,
                  classEdges
                )
              )
            LOG.info(
              s"[purrism-graph] serialized ${json.length} chars of JSON, handing off to dialog"
            )
            ApplicationManager.getApplication
              .invokeLater(() => dialog.renderGraph(json))
          } catch {
            case ex: Throwable =>
              LOG.warn("[purrism-graph] failed to build module graph", ex)
              ApplicationManager.getApplication.invokeLater(() =>
                Messages.showErrorDialog(
                  project,
                  ex.getMessage,
                  "Purrism Module Graph Failed"
                )
              )
          }
        }
      })
  }

  /** Finds `<classesRoot>/META-INF/semanticdb` directories under `basePath`,
    * pruning dot-dirs, `worktrees`, and (crucially for this repo)
    * `sandbox`/`standalone-sandbox` -- the full IDE-platform copies
    * `runIde`/`run-ide.sh` stage under `out/ijPlugin/`, which are hundreds of
    * thousands of files and would otherwise dominate the walk.
    * `SemanticIndex.fromRoots`'s own walker only prunes dot-dirs and
    * `worktrees`, not those, so this repo needs its own pruning.
    */
  private def findSemanticdbClasspathRoots(basePath: Path): Seq[Path] = {
    if (!Files.exists(basePath)) return Nil
    val found = scala.collection.mutable.ListBuffer.empty[Path]
    Files.walkFileTree(
      basePath,
      new SimpleFileVisitor[Path] {
        override def preVisitDirectory(
            dir: Path,
            attrs: BasicFileAttributes
        ): FileVisitResult = {
          val name = Option(dir.getFileName).map(_.toString).getOrElse("")
          if (name == "semanticdb") {
            val metaInf = dir.getParent
            if (metaInf != null && metaInf.getFileName.toString == "META-INF") {
              val classesRoot = metaInf.getParent
              if (classesRoot != null) found += classesRoot
            }
            return FileVisitResult.SKIP_SUBTREE
          }
          val skip = dir != basePath && (name.startsWith(".") ||
            name == "worktrees" || name == "sandbox" || name == "standalone-sandbox" || name == "node_modules")
          if (skip) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
        }

        override def visitFileFailed(
            file: Path,
            exc: IOException
        ): FileVisitResult = FileVisitResult.CONTINUE
      }
    )
    found.distinct.toSeq
  }
}

class PurrismGraphDialog(project: Project)
    extends DialogWrapper(project, false) {
  LOG.info("[purrism-graph] dialog constructed")
  private val browser = new JBCefBrowser(ViewerAssets.graphIndexUrl)
  private var pendingJson: Option[String] = None
  private var loaded = false

  browser.getJBCefClient.addLoadHandler(
    new CefLoadHandlerAdapter {
      override def onLoadEnd(
          cefBrowser: CefBrowser,
          frame: org.cef.browser.CefFrame,
          httpStatusCode: Int
      ): Unit = {
        LOG.info(
          s"[purrism-graph] page load finished url=${cefBrowser.getURL} status=$httpStatusCode"
        )
        loaded = true
        pendingJson.foreach(sendJson)
      }

      override def onLoadError(
          cefBrowser: CefBrowser,
          frame: org.cef.browser.CefFrame,
          errorCode: org.cef.handler.CefLoadHandler.ErrorCode,
          errorText: String,
          failedUrl: String
      ): Unit =
        LOG.warn(
          s"[purrism-graph] page load error url=$failedUrl code=$errorCode text=$errorText"
        )
    },
    browser.getCefBrowser
  )

  setTitle("Purrism Module Graph")
  // DialogWrapper is modal by default -- `show()` would then block the EDT until the user closes
  // it, which meant the ProgressManager.run(...) call right after `dialog.show()` in
  // PurrismGraphAction never actually started until the dialog was closed. Non-modal so the
  // background graph-building task can run while the (currently "waiting for data") dialog is up.
  setModal(false)
  init()

  def renderGraph(json: String): Unit = {
    LOG.info(
      s"[purrism-graph] renderGraph called, loaded=$loaded, ${json.length} chars"
    )
    pendingJson = Some(json)
    if (loaded) sendJson(json)
  }

  /** Base64-encodes the JSON before handing it to JS: a raw JSON string spliced
    * into a JS template literal only needs its own backslashes/backticks
    * escaped, but any `$` + `{` sequence in the payload (e.g. a Scala synthetic
    * symbol name like `Foo$default$1`, or a name containing a literal brace)
    * would still be read as a template-literal interpolation and silently
    * corrupt or fail the call with no visible error. Base64 has no such special
    * characters.
    */
  private def sendJson(json: String): Unit = {
    LOG.info(
      s"[purrism-graph] sending ${json.length} chars of graph JSON to the page"
    )
    val encoded = java.util.Base64.getEncoder
      .encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    browser.getCefBrowser.executeJavaScript(
      s"""window.purrismRenderGraphBase64 && window.purrismRenderGraphBase64("$encoded");""",
      browser.getCefBrowser.getURL,
      0
    )
  }

  override def createCenterPanel(): JComponent = {
    val panel = new javax.swing.JPanel(new BorderLayout())
    panel.setPreferredSize(new java.awt.Dimension(900, 650))
    panel.add(browser.getComponent, BorderLayout.CENTER)

    // JCEF's built-in right-click "Reload" context-menu item doesn't fire reliably inside the IDE's
    // embedded browser, so pure HTML/JS edits (see PURRISM_WEB_DIR dev mode) had no way to show up
    // without closing and reopening this whole dialog. reloadIgnoreCache() bypasses any caching of
    // the file:// page so an edited purrism-graph.html always wins.
    val reloadButton = new javax.swing.JButton("Reload viewer")
    reloadButton.addActionListener(_ =>
      browser.getCefBrowser.reloadIgnoreCache()
    )
    val controls = new javax.swing.JPanel()
    controls.add(reloadButton)
    panel.add(controls, BorderLayout.SOUTH)

    panel
  }
}
