#!/usr/bin/env scala-cli

//> using scala 3.3.8
//> using dep com.lihaoyi::upickle:4.4.3
//> using dep com.lihaoyi::os-lib:0.11.8

import java.io.BufferedReader
import java.io.InputStreamReader
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import ujson.Value

object LogScalaInteraction:
  def main(args: Array[String]): Unit =
    try {
      val reader = new BufferedReader(new InputStreamReader(System.in))
      val sb = new StringBuilder()
      var line = reader.readLine()
      while line != null do
        sb.append(line)
        line = reader.readLine()

      val jsonStr = sb.toString()
      if jsonStr.trim.isEmpty then sys.exit(0)

      val data = ujson.read(jsonStr)
      val tool = data.obj.get("tool_name").flatMap(_.strOpt).getOrElse("")
      val inp = data.obj
        .get("tool_input")
        .map(_.obj)
        .getOrElse(Map.empty[String, Value])
      val cwd = data.obj.get("cwd").flatMap(_.strOpt).getOrElse("")

      def checkTarget(): Option[String] =
        tool match
          case "Read" | "Edit" | "Write" | "MultiEdit" | "NotebookEdit" =>
            inp.get("file_path").flatMap { v =>
              v.strOpt.flatMap { str =>
                if str.endsWith(".scala") then Some(str) else None
              }
            }
          case "Grep" | "Glob" =>
            val pattern = inp.get("pattern").map(_.toString).getOrElse("")
            val glob = inp.get("glob").map(_.toString).getOrElse("")
            val path = inp.get("path").map(_.toString).getOrElse("")
            val combined = s"$pattern $glob $path"
            if combined.toLowerCase.contains("scala") then Some(combined.trim)
            else None
          case "Bash" =>
            inp.get("command").flatMap { v =>
              v.strOpt.flatMap { str =>
                if str.contains(".scala") then Some(str) else None
              }
            }
          case _ => None

      checkTarget() match
        case Some(target) =>
          val op = tool match
            case "Read"          => "read"
            case "Grep" | "Glob" => "search"
            case "Bash"          => "bash"
            case _               => "write"

          val ts =
            LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
          val record = ujson.Obj(
            "ts" -> ts,
            "tool" -> tool,
            "op" -> op,
            "target" -> target.take(500),
            "cwd" -> cwd
          )

          val logPathStr = System
            .getenv()
            .getOrDefault(
              "SCALA_INTERACTION_LOG",
              s"${System.getProperty("user.home")}/.claude/scala-interactions.jsonl"
            )
          val logPath = os.Path(logPathStr)
          os.makeDir.all(logPath / os.up)
          os.write.append(logPath, record.render() + "\n")
        case None => ()
    } catch {
      case _: Exception => ()
    }
    sys.exit(0)
