package net.runne.sitelinkvalidator

import java.nio.file.Path

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import com.fasterxml.aalto.WFCException

import scala.util.matching.Regex

object Reporter {

  trait Messages

  final case class FileChecked(file: Path) extends Messages

  final case class UrlChecked(url: String) extends Messages

  final case class UrlRedirect(url: String) extends Messages

  final case class UrlFailed(origin: Path, url: String, responseCode: Int)
    extends Messages

  final case class Missing(origin: Path, file: Path) extends Messages

  final case class FileErrored(file: Path, e: Throwable) extends Messages

  case class RequestReport(replyTo: ActorRef[ReportSummary]) extends Messages

  case class ReportSummary(errors: Set[FileErrored] = Set.empty,
                           missing: Set[Missing] = Set.empty,
                           urlFailures: Set[UrlFailed] = Set.empty) {
    def addError(e: FileErrored): ReportSummary = copy(errors = errors + e)

    def addMissing(e: Missing): ReportSummary = copy(missing = missing + e)

    def addUrlFailure(e: UrlFailed): ReportSummary =
      copy(urlFailures = urlFailures + e)

    def print(rootDir: Path, ignoreFilter: Regex): Unit = {
      def report(relFile: String) =
        ignoreFilter.findFirstMatchIn(relFile).isEmpty

      errors.toIndexedSeq
        .sortBy(_.file.toString)
        .foreach {
          case FileErrored(file, e: WFCException) =>
            val relFile = rootDir.relativize(file).toString
            if (report(relFile)) {
              println(
                s"ERROR $relFile L${e.getLocation.getLineNumber}:${e.getLocation.getColumnNumber} ${e.getMessage.replace("\n", " ")}"
              )
            }
          case FileErrored(file, e) =>
            val relFile = rootDir.relativize(file).toString
            if (report(relFile)) {
              println(s"ERROR $relFile $e")
            }
        }
      missing.toIndexedSeq
        .sortBy(_.file.toString)
        .foreach { m =>
          val relFile = rootDir.relativize(m.file).toString
          if (report(relFile)) {
            println(
              s"MISSING $relFile (referenced from ${rootDir.relativize(m.origin)})")
          }
        }
      urlFailures.toIndexedSeq
        .sortBy(_.url.toString)
        .foreach { m =>
          println(
            s"URL failure ${m.responseCode} ${m.url} (referenced from ${
              rootDir
                .relativize(m.origin)
            })")
        }

    }
  }

  def apply(): Behavior[Messages] = apply(ReportSummary())

  private def apply(reportSummary: ReportSummary): Behavior[Messages] =
    Behaviors.receiveMessage {
      case RequestReport(replyTo) =>
        replyTo ! reportSummary
        Behaviors.stopped

      case m: FileErrored => apply(reportSummary.addError(m))
      case m: UrlFailed => apply(reportSummary.addUrlFailure(m))
      case m: Missing => apply(reportSummary.addMissing(m))
      case _: FileChecked | _: UrlChecked => Behaviors.same
    }
}
