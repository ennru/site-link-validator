package net.runne.sitelinkvalidator

import java.net.HttpURLConnection
import java.nio.file.Path

import akka.Done
import akka.actor.CoordinatedShutdown
import akka.actor.typed.scaladsl.Behaviors
import akka.pattern.pipe
import akka.actor.typed.{ ActorRef, Behavior, Terminated }
import akka.dispatch.ExecutionContexts
import akka.http.scaladsl.{ Http, HttpExt }
import akka.http.scaladsl.model.{ HttpMethod, HttpMethods, HttpRequest, HttpResponse }
import akka.stream.SystemMaterializer

import scala.concurrent.ExecutionContext
import scala.util.{ Failure, Success, Try }

object UrlTester {
  val urlTimeoutInt = 343
  private[this] val HttpOk = 200
  private[this] val HttpRedirect = 301

  sealed trait Messages

  final case class Url(origin: Path, url: String) extends Messages

  final case class UrlResult(url: String, status: Int, referrer: Path) extends Messages

  final case class UrlError(url: String, e: Throwable, referrer: Path) extends Messages

  final case class RequestReport(replyTo: ActorRef[ReportSummary]) extends Messages

  object Shutdown extends Messages

  object Completed

  case class ReportSummary(urlCounters: Map[String, Set[Path]] = Map.empty, status: Map[String, Int] = Map.empty) {
    def contains(url: String) = urlCounters.keySet.contains(url)

    def count(url: String, referringFile: Path): ReportSummary = {
      val files = urlCounters.getOrElse(url, Set.empty)
      copy(urlCounters = urlCounters.updated(url, files + referringFile))
    }

    def testResult(res: UrlResult): ReportSummary = {
      copy(status = status.updated(res.url, res.status))
    }

    def print(rootDir: Path, nonHttpsWhitelist: Seq[String], limit: Int = 30, filesPerUrl: Int = 2): Seq[String] = {
      Seq("## Top linked pages") ++
      topPages(limit).flatMap {
        case (files, url, status) =>
          Seq(s"${files.size} links to $url status ${status.map(_.toString).getOrElse("")}") ++ {
            if (status.contains(HttpOk)) Seq()
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
        case (url, files) =>
          Seq(s"$url") ++ listFiles(files, rootDir, filesPerUrl)
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
          case (url, status) if status != HttpOk => true
          case _                                 => false
        }
        .toList
        .sortBy(t => (t._2, t._1))
        .map {
          case (url, status) =>
            (url, status, urlCounters.getOrElse(url, Set.empty))
        }
    }

    private def redirectPages = {
      status
        .filter {
          case (url, status) if status == HttpRedirect => true
          case _                                       => false
        }
        .keys
        .toList
        .map { url =>
          url -> urlCounters.getOrElse(url, Set.empty)
        }
    }

    private def listFiles(f: Set[Path], rootDir: Path, filesPerUrl: Int) =
      f.toSeq.take(filesPerUrl).toList.map(f => " - " + rootDir.relativize(f).toString)

    private def topPages(limit: Int) = {
      urlCounters.toSeq.sortBy(-_._2.size).take(limit).map {
        case (url, files) => (files, url, status.get(url))
      }
    }
  }

  def apply(): Behavior[Messages] = {
    Behaviors.setup { context =>
      CoordinatedShutdown(context.system)
        .addTask(CoordinatedShutdown.PhaseActorSystemTerminate, "shut-down-client-http-pool") { () =>
          Http(context.system).shutdownAllConnectionPools().map(_ => Done)(context.executionContext)
        }
      apply(ReportSummary(), running = 0, None)
    }
  }

  private def apply(
      reportSummary: ReportSummary,
      running: Int,
      reportTo: Option[ActorRef[ReportSummary]]): Behavior[Messages] =
    Behaviors.receive[Messages] { (context, message) =>
      message match {
        case Url(origin, url) =>
          val nowRunning =
            if (!reportSummary.contains(url)) {
              val request = HttpRequest(HttpMethods.HEAD, url)
              val response = Http(context.system).singleRequest(request)
              context.pipeToSelf(response) {
                case Success(res) =>
                  res.entity.discardBytes(SystemMaterializer(context.system).materializer)
                  UrlResult(url, res.status.intValue(), origin)
                case Failure(res) =>
                  UrlError(url, res, origin)
              }
              running + 1
            } else running
          apply(reportSummary.count(url, origin), nowRunning, reportTo)

        case msg: UrlResult =>
          val summary = reportSummary.testResult(msg)
          if (running == 1) {
            reportTo.foreach(ref => ref ! summary)
          }
          apply(summary, running - 1, reportTo)

        case msg: UrlError =>
          if (running == 1) {
            reportTo.foreach(ref => ref ! reportSummary)
          }
          apply(reportSummary, running - 1, reportTo)

        case RequestReport(replyTo) if running == 0 =>
          replyTo ! reportSummary
          Behaviors.same

        case RequestReport(replyTo) =>
          apply(reportSummary, running, reportTo = Some(replyTo))

        case Shutdown if running == 0 =>
          Behaviors.stopped

        case Shutdown =>
          shuttingDown(running)
      }
    }

  private def shuttingDown(running: Int): Behavior[Messages] =
    if (running == 0) Behaviors.stopped
    else
      Behaviors.receive[Messages] { (context, message) =>
        message match {
          case msg: UrlResult =>
            shuttingDown(running - 1)
          case msg: UrlError =>
            shuttingDown(running - 1)
        }
      }

}
