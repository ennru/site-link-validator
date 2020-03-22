package net.runne.sitelinkvalidator

import java.nio.file.Path

import akka.Done
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, Behavior }
import akka.stream.scaladsl.{ Sink, Source }
import akka.stream.{ Materializer, SystemMaterializer }
import org.jsoup.Jsoup

import scala.collection.immutable
import scala.concurrent.Future
import scala.jdk.CollectionConverters._
import scala.util.{ Failure, Success }

object HtmlFileReader {

  sealed trait Messages

  final case class FilePath(file: Path, replyTo: ActorRef[Completed.type]) extends Messages

  case object Completed extends Messages

  sealed trait FoundData

  final case class AbsoluteLink(s: String) extends FoundData

  final case class AnchorLink(s: String) extends FoundData

  final case class Link(s: String) extends FoundData

  final case class Anchor(s: String) extends FoundData

  case class Config(rootDir: Path, linkMappings: Map[String, String], ignorePrefixes: Seq[String])

  def reader(
      config: Config,
      reporter: ActorRef[Reporter.Messages],
      anchorValidator: ActorRef[AnchorValidator.Messages],
      urlTester: ActorRef[UrlTester.Messages],
      linkCollector: ActorRef[LinkCollector.Messages]): Behavior[Messages] =
    Behaviors.receive {
      def checkLocalLink(file: Path, link: String) = {
        val (path, anchor) = splitLinkAnchor(link)
        if (path.nonEmpty) {
          val f =
            if (path.startsWith("/")) config.rootDir.resolve(path.drop(1))
            else file.getParent.resolve(path).normalize
          linkCollector ! LinkCollector.FileLocation(file, f)
          if (anchor.nonEmpty) {
            anchorValidator ! AnchorValidator.Link(file, f, anchor)
          }
        } else if (anchor.nonEmpty) {
          anchorValidator ! AnchorValidator.Link(file, file, anchor)
        }
      }

      (context, message) =>
        message match {
          case FilePath(file, replyTo) =>
            implicit val mat: Materializer = SystemMaterializer(context.system).materializer
            val document = Jsoup.parse(file.toFile, "UTF-8", "/")
            val links = document.select("a[href]")
            val fileReader: Future[Done] =
              Source(links.asScala.toList)
                .map { element =>
                  val href = element.attr("abs:href")
                  if (href.startsWith("http")) AbsoluteLink(href)
                  else {
                    val href2 = element.attr("href")
                    if (href2.startsWith("#")) AnchorLink(href2.drop(1))
                    else Link(href2)
                  }
                }
                .runWith(Sink.foreach {
                  case AbsoluteLink(link) if config.ignorePrefixes.forall(prefix => !link.startsWith(prefix)) =>
                    config.linkMappings
                      .collectFirst {
                        case (prefix, path) if link.startsWith(prefix) =>
                          (prefix, path)
                      }
                      .fold {
                        val (path, _) = splitLinkAnchor(link)
                        urlTester ! UrlTester.Url(file, path)
                      } {
                        case (prefix, path) =>
                          val patchedLink = link.substring(prefix.length)
                          checkLocalLink(file, path + patchedLink)
                      }

                  case AbsoluteLink(link) =>
                  case Link(link) if (link.contains(".html")) =>
                    config.linkMappings
                      .collectFirst {
                        case (prefix, path) if link.startsWith(prefix) =>
                          (prefix, path)
                      }
                      .fold {
                        checkLocalLink(file, link)
                      } {
                        case (prefix, path) =>
                          val patchedLink = link.substring(prefix.length)
                          checkLocalLink(file, path + patchedLink)
                      }
                  case Link("") =>
                  case AnchorLink(anchor) =>
                    anchorValidator ! AnchorValidator.Link(file, file, anchor)
                })
            val anchors = document.select("a[name]")
            val ids = document.select("a[id]")
            val anchorReader: Future[Done] =
              Source(anchors.asScala.toList)
                .map(_.attr("name"))
                .concat(Source(ids.asScala.toList).map(_.attr("id")))
                .filter(_.nonEmpty)
                .map(Anchor)
                .runWith(Sink.foreach {
                  case Anchor(name) =>
                    anchorValidator ! AnchorValidator.Anchor(file, name)
                })
            implicit val ec = context.system.executionContext
            Future.sequence(immutable.Seq(fileReader, anchorReader)).onComplete {
              case Success(_) =>
                reporter ! Reporter.FileChecked(file)
                replyTo ! Completed
                context.self ! Completed
              case Failure(e) =>
                reporter ! Reporter.FileErrored(file, e)
                replyTo ! Completed
                context.self ! Completed
            }
            Behaviors.same

          case Completed =>
            Behaviors.stopped
        }
    }

  private def splitLinkAnchor(link: String) = {
    val p = link.indexOf('#')
    if (p == -1) {
      (link, "")
    } else {
      (link.substring(0, p), link.substring(p + 1))
    }

  }
}
