package net.runne.sitelinkvalidator

import java.nio.file.Path

import akka.actor.typed.ActorRef
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

import scala.jdk.CollectionConverters._

object HtmlFileReader {

  sealed trait FoundData

  final case class AbsoluteLink(s: String) extends FoundData

  final case class AnchorLink(s: String) extends FoundData

  final case class Link(s: String) extends FoundData

  case class Config(rootDir: Path, linkMappings: Map[String, String], ignorePrefixes: Seq[String])

  def findLinks(
      config: Config,
      reporter: ActorRef[Reporter.Messages],
      anchorValidator: ActorRef[AnchorValidator.Messages],
      urlTester: ActorRef[UrlTester.Messages],
      linkCollector: ActorRef[LinkCollector.FileLocation],
      file: Path): Unit = {

    if (file.toFile.isFile) {
      val document = Jsoup.parse(file.toFile, "UTF-8", "/")
      val linksInDocument = document.select("a[href]").asScala.toList
      checkLinks(file, linksInDocument)

      val anchors = document.select("a[name]").asScala.toList
      val ids = document.select("a[id]").asScala.toList
      collectAnchors(file, anchors, ids)
    } else {
      reporter ! Reporter.FileErrored(file, new RuntimeException(s"$file is not a file"))
    }

    def collectAnchors(file: Path, anchors: List[Element], ids: List[Element]) = {
      val all = anchors.map(_.attr("name")).concat(ids.map(_.attr("id"))).filter(_.nonEmpty).toSet
      anchorValidator ! AnchorValidator.Anchor(file, all)
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

    def checkLinks(file: Path, linksInDocument: List[Element]) =
      linksInDocument
        .map { element =>
          val href = element.attr("abs:href")
          if (href.startsWith("http")) AbsoluteLink(href)
          else {
            val href2 = element.attr("href")
            if (href2.startsWith("#")) AnchorLink(href2.drop(1))
            else Link(href2)
          }
        }
        .foreach {
          case AbsoluteLink(link) if config.ignorePrefixes.forall(prefix => !link.startsWith(prefix)) =>
            applyLinkMappings(file, link) {
              val (path, _) = splitLinkAnchor(link)
              urlTester ! UrlTester.Url(file, path)
            }

          case AbsoluteLink(link) =>
          // ignored

          case Link(link) =>
            applyLinkMappings(file, link) {
              checkLocalLink(file, link)
            }

          case AnchorLink(anchor) =>
            anchorValidator ! AnchorValidator.Link(file, file, anchor)
        }

    def splitLinkAnchor(link: String) = {
      val p = link.indexOf('#')
      if (p == -1) {
        (link, "")
      } else {
        (link.substring(0, p), link.substring(p + 1))
      }

    }
  }
}
