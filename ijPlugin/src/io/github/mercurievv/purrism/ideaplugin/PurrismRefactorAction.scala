package io.github.mercurievv.purrism.ideaplugin

import com.intellij.notification.{NotificationGroupManager, NotificationType}
import com.intellij.openapi.actionSystem.{AnAction, AnActionEvent}
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.{LocalFileSystem, VirtualFile}

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/** Runs scala-purrism scalafix rules on the current file via the
  * scalafix-interfaces API, using this plugin's own classloader so the rule
  * classes from the `scalafix` module (a moduleDep of this plugin) are found
  * without a network fetch.
  */
class PurrismRefactorAction extends AnAction {

  private val defaultRules =
    "TypelevelPurrism,TypeclassWeakening,PreferKleisli,PreferArrow,PreferCatsSyntax,SimplifyCatsExpressions"

  override def actionPerformed(e: AnActionEvent): Unit = {
    val project = e.getProject
    val file =
      e.getData(com.intellij.openapi.actionSystem.CommonDataKeys.VIRTUAL_FILE)
    if (project == null || file == null) return

    val rulesInput = Messages.showInputDialog(
      project,
      "Comma-separated scalafix rule names to run:",
      "Apply Purrism Scalafix Rules",
      Messages.getQuestionIcon,
      defaultRules,
      null
    )
    if (rulesInput == null || rulesInput.isBlank) return
    val rules = rulesInput.split(",").map(_.trim).filter(_.nonEmpty).toList

    try applyRules(project, file, rules)
    catch {
      case ex: Throwable =>
        Messages.showErrorDialog(
          project,
          ex.getMessage,
          "Purrism Scalafix Failed"
        )
    }
  }

  private def applyRules(
      project: Project,
      file: VirtualFile,
      rules: List[String]
  ): Unit = {
    val basePath = Paths.get(project.getBasePath)
    val semanticdbClasspath = findSemanticdbClasspathRoots(basePath)

    val api =
      scalafix.interfaces.Scalafix.classloadInstance(getClass.getClassLoader)
    val args = api
      .newArguments()
      .withRules(rules.asJava)
      .withPaths(java.util.List.of(file.toNioPath))
      .withSourceroot(basePath)
      .withClasspath(semanticdbClasspath.asJava)

    val evaluation = args.evaluate()
    if (!evaluation.isSuccessful) {
      val msg = evaluation.getErrorMessage.orElse("unknown error")
      Messages.showErrorDialog(project, msg, "Purrism Scalafix Failed")
      return
    }

    val fileEvals = evaluation.getFileEvaluations
    if (fileEvals.isEmpty) {
      notify(project, "No rules matched this file.")
      return
    }
    var changed = false
    for (fileEval <- fileEvals) {
      if (fileEval.isSuccessful) {
        val preview = fileEval.previewPatches()
        if (preview.isPresent) {
          Files.writeString(fileEval.getEvaluatedFile, preview.get)
          changed = true
        }
      } else {
        val msg = fileEval.getErrorMessage.orElse("unknown per-file error")
        Messages.showErrorDialog(project, msg, "Purrism Scalafix Failed")
      }
    }
    if (changed) {
      LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file.toNioPath)
      notify(project, s"Applied: ${rules.mkString(", ")}")
    } else {
      notify(project, "No changes needed.")
    }
  }

  /** Scans for `META-INF/semanticdb` directories under the project so semantic
    * rules can resolve prior compiler output; without this, semantic
    * (non-syntactic) rules fail with a missing-semanticdb error.
    */
  private def findSemanticdbClasspathRoots(basePath: Path): List[Path] = {
    if (!Files.exists(basePath)) return Nil
    val found = scala.collection.mutable.ListBuffer.empty[Path]
    Files
      .walk(basePath, 8)
      .iterator()
      .asScala
      .filter(p =>
        Files.isDirectory(p) && p.getFileName.toString == "semanticdb"
      )
      .foreach { semanticdbDir =>
        // semanticdb dir layout: <classesRoot>/META-INF/semanticdb/...
        val metaInf = semanticdbDir.getParent
        if (metaInf != null && metaInf.getFileName.toString == "META-INF") {
          val classesRoot = metaInf.getParent
          if (classesRoot != null) found += classesRoot
        }
      }
    found.distinct.toList
  }

  private def notify(project: Project, text: String): Unit =
    NotificationGroupManager
      .getInstance()
      .getNotificationGroup("Purrism")
      .createNotification(text, NotificationType.INFORMATION)
      .notify(project)
}
