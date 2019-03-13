package net.runne.sitelinkvalidator

import java.nio.file.Path

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors

import scala.util.matching.Regex

object AnchorValidator {

  trait Messages

  final case class Anchor(dir: Path, anchor: String) extends Messages

  final case class Link(origin: Path, target: Path, anchor: String)
    extends Messages

  final case class RequestReport(replyTo: ActorRef[Report]) extends Messages

  final case class Anchors(seen: Set[String] = Set.empty,
                           requested: Set[(Path, String)] = Set.empty) {
    def addSeen(name: String): Anchors =
      if (seen.contains(name)) this
      else Anchors(seen + name, requested)

    def addRequested(pathAnchor: (Path, String)): Anchors =
      if (requested.contains(pathAnchor)) this
      else Anchors(seen, requested + pathAnchor)
  }

  case class Report(data: Map[Path, Anchors] = Map.empty) {
    def addAnchor(file: Path, name: String): Report =
      copy(data =
        data.updated(file, data.getOrElse(file, Anchors()).addSeen(name)))

    def addLink(origin: Path, file: Path, anchor: String): Report =
      copy(
        data = data.updated(
          file,
          data.getOrElse(file, Anchors()).addRequested(origin -> anchor)))

    def report(rootDir: Path, ignoreFilter: Regex): Unit = {
      def report(relFile: String) =
        ignoreFilter.findFirstMatchIn(relFile).isEmpty

      data.foreach {
        case (path, anchors) =>
          val unseen = anchors.requested.map(_._2) -- anchors.seen
          if (unseen.nonEmpty) {
            val relFile = rootDir.relativize(path).toString
            if (report(relFile)) {
              println(s"Missing anchor in $relFile: ${unseen.mkString(", ")}")
              anchors.requested
                .filter {
                  case (p, an) =>
                    unseen.contains(an)
                }
                .take(5)
                .foreach {
                  case (p, an) =>
                    println(
                      s"  ${rootDir.relativize(p)} ${path.getFileName}#$an")
                }
            }
          }
      }

    }
  }

  def apply(report: Report = Report()): Behavior[Messages] =
    Behaviors.receiveMessage {
      case Anchor(file, name) if name.nonEmpty =>
        apply(report.addAnchor(file, name))

      case Link(origin, file, anchor) if anchor.nonEmpty =>
        apply(report.addLink(origin, file, anchor))

      case RequestReport(replyTo) =>
        replyTo ! report
        Behaviors.stopped

      case _ =>
        Behaviors.same
    }

}
