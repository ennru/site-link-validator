package net.runne.sitelinkvalidator

import java.net.HttpURLConnection
import java.nio.file.Path

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, Terminated}

object UrlTester {
  val urlTimeoutInt = 343
  private[this] val HttpOk = 200

  sealed trait Messages

  final case class Url(origin: Path, url: String) extends Messages

  final case class RequestReport(replyTo: ActorRef[ReportSummary]) extends Messages
  object Shutdown extends Messages

  object Completed

  case class ReportSummary(urlCounters: Map[String, Int] = Map.empty) {
    def count(url: String): ReportSummary =
      urlCounters.get(url).fold {
        ReportSummary(urlCounters.updated(url, 1))
      }{ value =>
        ReportSummary(urlCounters.updated(url, value + 1))
      }

    def print(limit: Int = 30): Seq[String] =
      urlCounters.toSeq.sortBy(-_._2).take(limit).map {
        case (url, count) => s"$count links to $url"
      }
  }

  def apply(): Behavior[Messages] =
    apply(ReportSummary(), running = 0, None)

  private def apply(reportSummary: ReportSummary,
                    running: Int, reportTo: Option[ActorRef[ReportSummary]]): Behavior[Messages] =
    Behaviors
      .receive[Messages] { (context, message) =>
        message match {
          case Url(origin, url) =>
            apply(reportSummary.count(url), running, reportTo)
          //          val worker = context.spawnAnonymous(UrlTestWorker(reporter))
          //          worker ! UrlTestWorker.Url(origin, url)
          //          context.watch(worker)
          //          apply(reporter, tested + url, running + 1)

          case RequestReport(replyTo) if running == 0 =>
            replyTo ! reportSummary
            Behavior.same

          case RequestReport(replyTo)  =>
            apply(reportSummary, running, Some(replyTo))

          case Shutdown if running == 0 =>
            Behaviors.stopped

          case Shutdown =>
            shuttingDown(running)
        }
      }
      .receiveSignal {
        case (_, Terminated(_)) if running == 1 =>
          reportTo.foreach(ref => ref ! reportSummary)
          apply(reportSummary, running - 1, None)
        case (_, Terminated(_)) =>
          apply(reportSummary, running - 1, reportTo)
      }

  private def shuttingDown(running: Int): Behavior[Messages] =
    Behaviors
      .receiveSignal {
        case (_, Terminated(_)) if running == 1 =>
          Behaviors.stopped
        case (_, Terminated(_)) =>
          shuttingDown(running - 1)
      }

  object UrlTestWorker {
    val urlTimeoutInt = 3000
    private[this] val HttpOk = 200
    private[this] val HttpRedirect = 301

    sealed trait Messages

    final case class Url(origin: Path, url: String) extends Messages

    def apply(reporter: ActorRef[Reporter.Messages]): Behavior[Messages] =
      Behaviors.receive { (context, message) =>
        message match {
          case Url(origin, url) =>
            connectTo(url) match {
              case HttpOk =>
                reporter ! Reporter.UrlChecked(url)
              case HttpRedirect =>
                reporter ! Reporter.UrlRedirect(url)
              case other =>
                reporter ! Reporter.UrlFailed(origin, url, other)
            }
            Behavior.stopped
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
