package net.runne.sitelinkvalidator

import java.nio.file.Path

import akka.Done
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, ActorSystem, Behavior }
import akka.stream.scaladsl.{ Sink, Source }
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

import scala.concurrent.Future
import scala.jdk.CollectionConverters._
import scala.util.{ Failure, Success }

object HtmlFileReader {

  sealed trait Messages

  final case class FilePath(file: Path, replyTo: ActorRef[Completed.type]) extends Messages

  case object CompletedLinks extends Messages

  case object CompletedAnchors extends Messages

  case object Completed extends Messages

  sealed trait FoundData

  final case class AbsoluteLink(s: String) extends FoundData

  final case class AnchorLink(s: String) extends FoundData

  final case class Link(s: String) extends FoundData

  case class Config(rootDir: Path, linkMappings: Map[String, String], ignorePrefixes: Seq[String])

  def reader(
      config: Config,
      reporter: ActorRef[Reporter.Messages],
      anchorValidator: ActorRef[AnchorValidator.Messages],
      urlTester: ActorRef[UrlTester.Messages],
      linkCollector: ActorRef[LinkCollector.Messages]) =
    new HtmlFileReader(config, reporter, anchorValidator, urlTester, linkCollector).behaviour
}

class HtmlFileReader(
    config: HtmlFileReader.Config,
    reporter: ActorRef[Reporter.Messages],
    anchorValidator: ActorRef[AnchorValidator.Messages],
    urlTester: ActorRef[UrlTester.Messages],
    linkCollector: ActorRef[LinkCollector.Messages]) {

  import HtmlFileReader._

  def behaviour: Behavior[Messages] =
    Behaviors.receive { (context, message) =>
      message match {
        case FilePath(file, replyTo) if file.toFile.isFile =>
          implicit val system = context.system

          val document = Jsoup.parse(file.toFile, "UTF-8", "/")
          val linksInDocument = document.select("a[href]").asScala.toList
          context.pipeToSelf(checkLinks(file, linksInDocument)) {
            case Success(_) => CompletedLinks
            case Failure(e) =>
              reporter ! Reporter.FileErrored(file, e)
              CompletedLinks
          }

          val anchors = document.select("a[name]").asScala.toList
          val ids = document.select("a[id]").asScala.toList
          context.pipeToSelf(checkAnchors(file, anchors, ids)) {
            case Success(_) => CompletedAnchors
            case Failure(e) =>
              reporter ! Reporter.FileErrored(file, e)
              CompletedAnchors
          }
          completing(false, false, replyTo)

        case FilePath(file, replyTo) =>
          context.log.error(s"can't read {}", file.toFile.getAbsolutePath)
          replyTo ! Completed
          Behaviors.stopped
      }
    }

  private def completing(links: Boolean, anchors: Boolean, replyTo: ActorRef[Completed.type]): Behavior[Messages] =
    Behaviors.receiveMessage {
      case CompletedLinks if anchors =>
        replyTo ! Completed
        Behaviors.stopped

      case CompletedLinks =>
        completing(true, anchors, replyTo)

      case CompletedAnchors if links =>
        replyTo ! Completed
        Behaviors.stopped

      case CompletedAnchors =>
        completing(links, true, replyTo)
    }

  private def checkAnchors(file: Path, anchors: List[Element], ids: List[Element])(implicit system: ActorSystem[_]) = {
    Source(anchors)
      .map(_.attr("name"))
      .concat(Source(ids).map(_.attr("id")))
      .filter(_.nonEmpty)
      .runWith(Sink.foreach { name =>
        anchorValidator ! AnchorValidator.Anchor(file, name)
      })
  }

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

  def applyLinkMappings(file: Path, link: String)(noMapping: => Unit) = {
    config.linkMappings
      .collectFirst {
        case (prefix, path) if link.startsWith(prefix) =>
          (prefix, path)
      }
      .fold(noMapping) {
        case (prefix, path) =>
          val patchedLink = link.substring(prefix.length)
          checkLocalLink(file, path + patchedLink)
      }
  }

  private def checkLinks(file: Path, linksInDocument: List[Element])(implicit system: ActorSystem[_]) = {
    val fileReader: Future[Done] =
      Source(linksInDocument)
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
            applyLinkMappings(file, link) {
              val (path, _) = splitLinkAnchor(link)
              urlTester ! UrlTester.Url(file, path)
            }

          case AbsoluteLink(link) =>
          // ignored

          case Link(link) if link.contains(".html") =>
            applyLinkMappings(file, link) {
              checkLocalLink(file, link)
            }

          case Link("") =>
          // ignored

          case AnchorLink(anchor) =>
            anchorValidator ! AnchorValidator.Link(file, file, anchor)
        })
    fileReader
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
