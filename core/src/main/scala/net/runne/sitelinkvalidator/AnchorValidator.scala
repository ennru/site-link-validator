package net.runne.sitelinkvalidator

import java.nio.file.Path

import akka.actor.typed.{ ActorRef, Behavior }
import akka.actor.typed.scaladsl.Behaviors

import scala.collection.immutable
import scala.util.matching.Regex

object AnchorValidator {

  trait Messages

  final case class Anchor(dir: Path, anchor: Set[String]) extends Messages

  final case class Link(origin: Path, target: Path, anchor: String) extends Messages

  final case class RequestReport(replyTo: ActorRef[Report]) extends Messages

  final case class Anchors(seen: Set[String] = Set.empty, requested: Set[(Path, String)] = Set.empty) {
    def addSeen(names: Set[String]): Anchors = Anchors(seen ++ names, requested)

    def addRequested(pathAnchor: (Path, String)): Anchors = {
      val anchor = pathAnchor._2.replaceAll("=%3E", "=>")
      val patchedPathAnchor = pathAnchor._1 -> anchor
      if (requested.contains(patchedPathAnchor)) this
      else Anchors(seen, requested + patchedPathAnchor)
    }
  }

  case class Report(data: Map[Path, Anchors] = Map.empty) {
    def addAnchors(file: Path, names: Set[String]): Report =
      copy(data = data.updated(file, data.getOrElse(file, Anchors()).addSeen(names)))

    def addLink(origin: Path, file: Path, anchor: String): Report =
      copy(data = data.updated(file, data.getOrElse(file, Anchors()).addRequested(origin -> anchor)))

    def report(rootDir: Path, ignoreFilter: Regex, limit: Int = 5): immutable.Seq[String] = {
      Seq("## Anchors") ++
      data
        .map {
          case (path, anchors) =>
            val unseen = anchors.requested.map(_._2) -- anchors.seen
            val relFile = rootDir.relativize(path).toString
            (path, anchors, unseen, relFile)
        }
        .filter {
          case (path, anchors, unseen, relFile) =>
            unseen.nonEmpty && ignoreFilter.findFirstMatchIn(relFile).isEmpty
        }
        .flatMap {
          case (path, anchors, unseen, relFile) =>
            Seq(relFile.toString, "requested") ++
            unseen.map(a => s" - $a") ++
            Seq("seen") ++
            anchors.seen.toList.sorted.map(a => s" - $a") ++
            Seq("requested in") ++
            anchors.requested
              .filter {
                case (p, an) =>
                  unseen.contains(an)
              }
              .take(limit)
              .map {
                case (p, an) =>
                  s" - ${rootDir.relativize(p)} ${path.getFileName}#$an"
              } ++
            Seq("")
        }
    }
  }

  def apply(report: Report = Report()): Behavior[Messages] =
    Behaviors.receiveMessage {
      case Anchor(file, names) if names.nonEmpty =>
        apply(report.addAnchors(file, names))

      case Link(origin, file, anchor) if anchor.nonEmpty =>
        apply(report.addLink(origin, file, anchor))

      case RequestReport(replyTo) =>
        replyTo ! report
        Behaviors.stopped

      case _ =>
        Behaviors.same
    }

}
