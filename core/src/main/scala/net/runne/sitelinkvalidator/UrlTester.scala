package net.runne.sitelinkvalidator

import java.nio.file.Path

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, ActorSystem, Behavior }
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Location
import akka.stream.typed.scaladsl.{ ActorSink, ActorSource }
import akka.stream.{ Materializer, OverflowStrategy, SystemMaterializer }

import scala.concurrent.ExecutionContext
import scala.util.{ Failure, Success }

object UrlSummary {

  case class Report(
      urlReferrers: Map[String, Set[Path]] = Map.empty,
      status: Map[String, StatusCode] = Map.empty,
      redirectTo: Map[String, Uri] = Map.empty) {
    def contains(url: String) = urlReferrers.contains(url)

    def count(url: String, referringFile: Path): Report = {
      val files = urlReferrers.getOrElse(url, Set.empty)
      copy(urlReferrers = urlReferrers.updated(url, files + referringFile))
    }

    def testResult(res: UrlResult): Report = {
      copy(
        status = status.updated(res.url, res.status),
        redirectTo = res.redirected.fold(redirectTo) { uri =>
          redirectTo.updated(res.url, uri)
        })
    }

    def testResult(res: UrlError): Report = {
      copy(status = status.updated(res.url, StatusCodes.custom(-1, res.e.toString, "", false, false)))
    }

    def print(rootDir: Path, nonHttpsWhitelist: Seq[String], limit: Int = 30, filesPerUrl: Int = 2): Seq[String] = {
      Seq("## Top linked pages") ++
      topPages(limit).flatMap { case (files, url, status) =>
        Seq(s"${files.size} links to `$url`   (${status.map(_.toString).getOrElse("")})") ++ {
          if (status.contains(StatusCodes.OK)) Seq()
          else listFiles(files, rootDir, filesPerUrl)
        }
      } ++ {
        if (nonOkPages.nonEmpty)
          Seq("", "## Non-HTTP OK pages") ++
          nonOkPages.flatMap { case (url, status, files) =>
            Seq(s"`$url` status ${status}") ++ listFiles(files, rootDir, filesPerUrl)
          }
        else Seq.empty
      } ++ {
        if (redirectPages.nonEmpty)
          Seq("", "## Redirected URLs") ++
          redirectPages.flatMap { case ((url, location), files) =>
            Seq(s"`$url` should be", s"`$location`") ++ listFiles(files, rootDir, filesPerUrl)
          }
        else Seq.empty
      } ++ {
        val nonHttpsPages = urlReferrers.toSeq
          .filter { case (url, files) =>
            url.startsWith("http://") && nonHttpsWhitelist.forall(white => !url.startsWith(white))
          }
          .sortBy(_._1)
        if (nonHttpsPages.nonEmpty)
          Seq("", "## Non-https pages") ++
          nonHttpsPages.flatMap { case (url, files) =>
            Seq(s"`$url`") ++ listFiles(files, rootDir, filesPerUrl)
          }
        else Seq.empty
      }
    }

    private def nonOkPages = {
      status.toList
        .filter { case (url, status) =>
          status != StatusCodes.OK && status != StatusCodes.MovedPermanently && status != StatusCodes.SeeOther
        }
        .sortBy { case (url, status) =>
          (status.intValue(), url)
        }
        .map { case (url, status) =>
          (url, status, urlReferrers.getOrElse(url, Set.empty))
        }
    }

    private def redirectPages = {
      redirectTo.toList.map { case (url, location) =>
        (url, location) -> urlReferrers.getOrElse(url, Set.empty)
      }
    }

    private def listFiles(f: Set[Path], rootDir: Path, filesPerUrl: Int) = {
      val seq = f.toList.sorted
      seq.take(filesPerUrl).map(f => " - " + rootDir.relativize(f).toString) ++ {
        if (seq.lengthCompare(filesPerUrl) > 0) Seq(s" - ... ${seq.length - filesPerUrl} more", "") else Seq("")
      }
    }

    private def topPages(limit: Int) = {
      urlReferrers.toSeq.sortBy(-_._2.size).take(limit).map { case (url, files) =>
        (files, url, status.get(url))
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

  final case class UrlRetry(referringFile: Path, url: String, method: HttpMethod) extends Messages

  final case class RequestReport(report: ActorRef[UrlSummary.Report]) extends Messages

  final case class Report(report: UrlSummary.Report) extends Messages

  private sealed trait QueueMessages

  private final case class QueueUrl(referringFile: Path, url: String, method: HttpMethod = HttpMethods.HEAD)
      extends QueueMessages

  private object QueueShutdown extends QueueMessages

  def apply(): Behavior[Messages] = {
    Behaviors.setup { context =>
      implicit val system: ActorSystem[Nothing] = context.system
      implicit val mat: Materializer = SystemMaterializer(context.system).materializer
      implicit val ec: ExecutionContext = system.executionContext
      //      val cs = CoordinatedShutdown(context.system)
      //      cs.addTask(CoordinatedShutdown.PhaseServiceStop, "shut-down-client-http-pool") { () =>
      //        Http(context.system).shutdownAllConnectionPools().map(_ => Done)(context.executionContext)
      //      }

      val summary: ActorRef[UrlSummary.Messages] = context.spawn(UrlSummary.apply(), "summary")
      val httpQueue: ActorRef[QueueMessages] = ActorSource
        .actorRef[QueueMessages](
          completionMatcher = { case QueueShutdown =>
          },
          failureMatcher = PartialFunction.empty,
          bufferSize = 5000,
          OverflowStrategy.dropTail)
        .collect { case u: QueueUrl =>
          u
        }
        .map { case qUrl @ QueueUrl(origin, url, method) =>
          val request = HttpRequest(method, url)
          request -> qUrl
        }
        .via(Http().superPool())
        .mapConcat {
          case (Success(res), QueueUrl(origin, url, HttpMethods.HEAD)) if res.status == StatusCodes.MethodNotAllowed =>
            context.self ! UrlRetry(origin, url, HttpMethods.GET)
            Seq.empty
          case (Success(res), QueueUrl(origin, url, method)) =>
            // Workaround for https://github.com/akka/akka-http/issues/3530
            if (method != HttpMethods.HEAD) res.discardEntityBytes()
            val redirectUri = res.headers
              .collectFirst { case loc: Location =>
                loc.uri
              }
              .filterNot { uri =>
                // don't report redirects adding/removing just the trailing slash
                url == uri.toString() + "/" || url + "/" == uri.toString()
              }
            Seq(UrlSummary.UrlResult(url, res.status, origin, redirectUri))
          case (Failure(res), QueueUrl(origin, url, _)) =>
            Seq(UrlSummary.UrlError(url, res, origin))
        }
        .to {
          ActorSink.actorRef[UrlSummary.Messages](
            summary,
            UrlSummary.RequestReport(context.messageAdapter(Report)),
            UrlSummary.StreamFailed)
        }
        .run()
      apply(testedUrls = Set.empty, httpQueue, summary)
    }
  }

  private def apply(
      testedUrls: Set[String],
      httpQueue: ActorRef[QueueMessages],
      summary: ActorRef[UrlSummary.Messages]): Behavior[Messages] =
    Behaviors.receiveMessagePartial {
      case UrlRetry(referringFile, url, method) =>
        httpQueue ! QueueUrl(referringFile, url, method)
        Behaviors.same
      case Url(referringFile, url) =>
        summary ! UrlSummary.UrlCount(url, referringFile)
        if (!testedUrls.contains(url)) {
          httpQueue ! QueueUrl(referringFile, url)
        }
        apply(testedUrls + url, httpQueue, summary)

      case RequestReport(replyTo) =>
        httpQueue ! QueueShutdown
        shuttingDown(replyTo)
    }

  private def shuttingDown(reportTo: ActorRef[UrlSummary.Report]): Behavior[Messages] =
    Behaviors.receivePartial { case (context, Report(report)) =>
      Http(context.system).shutdownAllConnectionPools()
      reportTo ! report
      Behaviors.stopped
    }

}
