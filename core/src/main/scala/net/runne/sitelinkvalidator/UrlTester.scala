package net.runne.sitelinkvalidator

import java.nio.file.Path

import akka.Done
import akka.actor.CoordinatedShutdown
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, Behavior }
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Location
import akka.stream.typed.scaladsl.{ ActorSink, ActorSource }
import akka.stream.{ OverflowStrategy, SystemMaterializer }

import scala.util.{ Failure, Success }

object UrlSummary {

  case class Report(
      urlCounters: Map[String, Set[Path]] = Map.empty,
      status: Map[String, StatusCode] = Map.empty,
      redirectTo: Map[String, Uri] = Map.empty) {
    def contains(url: String) = urlCounters.contains(url)

    def count(url: String, referringFile: Path): Report = {
      val files = urlCounters.getOrElse(url, Set.empty)
      copy(urlCounters = urlCounters.updated(url, files + referringFile))
    }

    def testResult(res: UrlResult): Report = {
      copy(status = status.updated(res.url, res.status), redirectTo = res.redirected.fold(redirectTo) { uri =>
        redirectTo.updated(res.url, uri)
      })
    }

    def testResult(res: UrlError): Report = {
      copy(status = status.updated(res.url, StatusCodes.custom(-1, res.e.toString, "", false, false)))
    }

    def print(rootDir: Path, nonHttpsWhitelist: Seq[String], limit: Int = 30, filesPerUrl: Int = 2): Seq[String] = {
      Seq("## Top linked pages") ++
      topPages(limit).flatMap {
        case (files, url, status) =>
          Seq(s"${files.size} links to $url status ${status.map(_.toString).getOrElse("")}") ++ {
            if (status.contains(StatusCodes.OK)) Seq()
            else listFiles(files, rootDir, filesPerUrl)
          }
      } ++
      Seq("", "## Non-HTTP OK pages") ++
      nonOkPages.flatMap {
        case (url, status, files) =>
          Seq(s"$url status ${status}") ++ listFiles(files, rootDir, filesPerUrl)
      } ++
      Seq("", "## Redirected URLs") ++
      redirectPages.flatMap {
        case ((url, location), files) =>
          Seq(s"$url should be", s"$location") ++ listFiles(files, rootDir, filesPerUrl)
      } ++
      Seq("", "## Non-https pages") ++
      urlCounters.toSeq
        .filter {
          case (url, files) => url.startsWith("http://") && nonHttpsWhitelist.forall(white => !url.startsWith(white))
        }
        .sortBy(_._1)
        .flatMap {
          case (url, files) =>
            Seq(s"$url") ++ listFiles(files, rootDir, filesPerUrl)
        }
    }

    private def nonOkPages = {
      status
        .filter {
          case (url, status) =>
            status != StatusCodes.OK && status != StatusCodes.MovedPermanently
        }
        .toList
        .sortBy(t => (t._2.intValue(), t._1))
        .map {
          case (url, status) =>
            (url, status, urlCounters.getOrElse(url, Set.empty))
        }
    }

    private def redirectPages = {
      redirectTo.toList.map {
        case (url, location) =>
          (url, location) -> urlCounters.getOrElse(url, Set.empty)
      }
    }

    private def listFiles(f: Set[Path], rootDir: Path, filesPerUrl: Int) = {
      val seq = f.toSeq
      seq.take(filesPerUrl).toList.map(f => " - " + rootDir.relativize(f).toString) ++ {
        if (seq.lengthCompare(filesPerUrl) > 0) Seq(" - ...", "") else Seq("")
      }
    }

    private def topPages(limit: Int) = {
      urlCounters.toSeq.sortBy(-_._2.size).take(limit).map {
        case (url, files) => (files, url, status.get(url))
      }
    }
  }

  sealed trait Messages

  final case class UrlCount(url: String, referringFile: Path) extends Messages

  final case class UrlResult(url: String, status: StatusCode, referrer: Path, redirected: Option[Uri]) extends Messages

  final case class UrlError(url: String, e: Throwable, referrer: Path) extends Messages

  final case class RequestReport(replyTo: ActorRef[UrlSummary.Report]) extends Messages

  final case class StreamFailed(e: Throwable) extends Messages

  def apply(): Behavior[Messages] = apply(Report())

  private def apply(reportSummary: Report): Behavior[Messages] = {
    Behaviors.receive {
      case (_, UrlCount(url, referringFile)) =>
        apply(reportSummary.count(url, referringFile))

      case (_, msg: UrlResult) =>
        apply(reportSummary.testResult(msg))

      case (_, msg: UrlError) =>
        apply(reportSummary.testResult(msg))

      case (_, RequestReport(replyTo)) =>
        replyTo ! reportSummary
        Behaviors.same

      case (context, StreamFailed(e)) =>
        context.log.error("URL testing failed", e)
        Behaviors.stopped
    }
  }

}

object UrlTester {

  sealed trait Messages

  final case class Url(referringFile: Path, url: String) extends Messages

  final case class RequestReport(report: ActorRef[UrlSummary.Report]) extends Messages

  final case class Report(report: UrlSummary.Report) extends Messages

  private sealed trait QueueMessages
  private final case class QueueUrl(referringFile: Path, url: String) extends QueueMessages
  private object QueueShutdown extends QueueMessages

  def apply(): Behavior[Messages] = {
    Behaviors.setup { context =>
      implicit val system = context.system
      implicit val ec = context.executionContext
      val cs = CoordinatedShutdown(context.system)
      cs.addTask(CoordinatedShutdown.PhaseServiceStop, "shut-down-client-http-pool") { () =>
        Http(context.system).shutdownAllConnectionPools().map(_ => Done)(context.executionContext)
      }

      val summary: ActorRef[UrlSummary.Messages] = context.spawn(UrlSummary.apply(), "summary")
      val httpQueue: ActorRef[QueueMessages] = ActorSource
        .actorRef[QueueMessages]({
          case QueueShutdown =>
        }, failureMatcher = PartialFunction.empty, bufferSize = 5000, OverflowStrategy.dropTail)
        .collect {
          case u: QueueUrl => u
        }
        .mapAsync(20) {
          case QueueUrl(origin, url) =>
            val request = HttpRequest(HttpMethods.HEAD, url)
            Http(context.system).singleRequest(request).transform {
              case Success(res) =>
                res.entity.discardBytes(SystemMaterializer(context.system).materializer)
                val redirectUri = res.headers.collectFirst {
                  case loc: Location => loc.uri
                }
                Success(UrlSummary.UrlResult(url, res.status, origin, redirectUri))
              case Failure(res) =>
                Success(UrlSummary.UrlError(url, res, origin))
            }
        }
        .to {
          ActorSink.actorRef[UrlSummary.Messages](
            summary,
            UrlSummary.RequestReport(context.messageAdapter(report => Report(report))),
            UrlSummary.StreamFailed)
        }
        .run()
      apply(testedUrls = Set.empty, httpQueue, summary, None)
    }
  }

  private def apply(
      testedUrls: Set[String],
      httpQueue: ActorRef[QueueMessages],
      summary: ActorRef[UrlSummary.Messages],
      replyTo: Option[ActorRef[UrlSummary.Report]] = None): Behavior[Messages] =
    Behaviors.receiveMessage {
      case Url(referringFile, url) =>
        summary ! UrlSummary.UrlCount(url, referringFile)
        if (!testedUrls.contains(url)) {
          httpQueue ! QueueUrl(referringFile, url)
        }
        apply(testedUrls + url, httpQueue, summary)

      case RequestReport(replyTo) =>
        httpQueue ! QueueShutdown
        apply(testedUrls, httpQueue, summary, replyTo = Some(replyTo))

      case Report(report) =>
        replyTo.foreach(_ ! report)
        Behaviors.stopped
    }

}
