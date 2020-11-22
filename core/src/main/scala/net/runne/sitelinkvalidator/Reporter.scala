package net.runne.sitelinkvalidator

import java.nio.file.Path

import akka.actor.typed.{ ActorRef, Behavior }
import akka.actor.typed.scaladsl.Behaviors

import scala.collection.immutable
import scala.util.matching.Regex

object Reporter {

  trait Messages

  final case class UrlChecked(url: String) extends Messages

  final case class UrlRedirect(url: String) extends Messages

  final case class UrlFailed(origin: Path, url: String, responseCode: Int) extends Messages

  final case class Missing(origin: Path, file: Path) extends Messages

  final case class FileErrored(file: Path, e: Throwable) extends Messages

  case class RequestReport(replyTo: ActorRef[ReportSummary]) extends Messages

  case class ReportSummary(
      errors: Set[FileErrored] = Set.empty,
      missing: Set[Missing] = Set.empty,
      urlFailures: Set[UrlFailed] = Set.empty) {
    def addError(e: FileErrored): ReportSummary = copy(errors = errors + e)

    def addMissing(e: Missing): ReportSummary = copy(missing = missing + e)

    def addUrlFailure(e: UrlFailed): ReportSummary =
      copy(urlFailures = urlFailures + e)

    def errorReport(rootDir: Path): Seq[(String, Throwable)] = {
      errors.toSeq.sortBy(_.file.toString).map { case FileErrored(file, e) =>
        val relFile = rootDir.relativize(file).toString
        relFile -> e
      }
    }

    def missingReport(rootDir: Path, ignoreFilter: Regex): Seq[(String, Path)] = {
      missing.toIndexedSeq
        .sortBy(_.file.toString)
        .map { missing =>
          (rootDir.relativize(missing.file).toString, missing)
        }
        .filter { case (relFile, m) =>
          ignoreFilter.findFirstMatchIn(relFile).isEmpty
        }
        .map { case (relFile, missing) =>
          relFile -> rootDir.relativize(missing.origin)
        }
    }

    def urlFailureReport(rootDir: Path): immutable.Seq[String] = {
      urlFailures.toIndexedSeq.sortBy(_.url.toString).map { m =>
        s"URL failure ${m.responseCode} `${m.url}`[${m.url}] (referenced from ${rootDir.relativize(m.origin)})"
      }
    }

    def report(dir: Path, ignoreMissingLocalFileFilter: Regex): Seq[String] = {
      {
        if (errors.nonEmpty)
          Seq("", "## Errors") ++
          errorReport(dir).map { case (file, error) =>
            s"$file triggered $error"
          }
        else Seq.empty
      } ++ {
        if (missing.nonEmpty)
          Seq("", "## Missing local files") ++
          missingReport(dir, ignoreMissingLocalFileFilter).map { case (file, referrer) =>
            s"$file referenced from $referrer"
          }
        else Seq.empty
      } ++ {
        if (urlFailures.nonEmpty)
          Seq("", "## URL Errors") ++
          urlFailureReport(dir)
        else Seq.empty
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
      case m: UrlFailed   => apply(reportSummary.addUrlFailure(m))
      case m: Missing     => apply(reportSummary.addMissing(m))
      case _: UrlChecked  => Behaviors.same
    }
}
