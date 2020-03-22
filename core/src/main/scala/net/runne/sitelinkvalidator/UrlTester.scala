package net.runne.sitelinkvalidator

import java.net.HttpURLConnection
import java.nio.file.Path

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, Behavior, Terminated }

object UrlTester {
  val urlTimeoutInt = 343
  private[this] val HttpOk = 200
  private[this] val HttpRedirect = 301

  sealed trait Messages

  final case class Url(origin: Path, url: String) extends Messages

  final case class UrlResult(url: String, status: Int, referrer: Path) extends Messages

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

  def apply(): Behavior[Messages] =
    apply(ReportSummary(), running = 0, None)

  private def apply(
      reportSummary: ReportSummary,
      running: Int,
      reportTo: Option[ActorRef[ReportSummary]]): Behavior[Messages] =
    Behaviors
      .receive[Messages] { (context, message) =>
        message match {
          case Url(origin, url) =>
            val nowRunning =
              if (!reportSummary.contains(url)) {
                val worker = context.spawnAnonymous(UrlTestWorker(context.self))
                worker ! UrlTestWorker.Url(origin, url)
                running + 1
              } else running
            apply(reportSummary.count(url, origin), nowRunning, reportTo)

          case msg: UrlResult =>
            val summary = reportSummary.testResult(msg)
            if (running == 1) {
              reportTo.foreach(ref => ref ! summary)
            }
            apply(summary, running - 1, reportTo)

          case RequestReport(replyTo) if running == 0 =>
            replyTo ! reportSummary
            Behaviors.same

          case RequestReport(replyTo) =>
            apply(reportSummary, running, Some(replyTo))

          case Shutdown if running == 0 =>
            Behaviors.stopped

          case Shutdown =>
            shuttingDown(running)
        }
      }
      .receiveSignal {
        case (_, Terminated(_)) if running == 1 =>
          apply(reportSummary, running - 1, None)
        case (_, Terminated(_)) =>
          apply(reportSummary, running - 1, reportTo)
      }

  private def shuttingDown(running: Int): Behavior[Messages] =
    Behaviors.receiveSignal {
      case (_, Terminated(_)) if running == 1 =>
        Behaviors.stopped
      case (_, Terminated(_)) =>
        shuttingDown(running - 1)
    }

  object UrlTestWorker {
    val urlTimeoutInt = 3000

    sealed trait Messages

    final case class Url(origin: Path, url: String) extends Messages

    def apply(reporter: ActorRef[UrlTester.UrlResult]): Behavior[Messages] =
      Behaviors.receive { (context, message) =>
        message match {
          case Url(origin, url) =>
            reporter ! UrlTester.UrlResult(url, connectTo(url), origin)
            Behaviors.stopped
        }
      }

    private def connectTo(url: String, timeout: Int = urlTimeoutInt): Int =
      try {
        val conn =
          new java.net.URL(url).openConnection().asInstanceOf[HttpURLConnection]
        conn.setRequestMethod("HEAD")
        conn.setConnectTimeout(timeout)
        conn.setReadTimeout(timeout)
        conn.getResponseCode
      } catch {
        case e: sun.security.validator.ValidatorException =>
          -1
        case e: Exception =>
          -2
      }
  }

}
