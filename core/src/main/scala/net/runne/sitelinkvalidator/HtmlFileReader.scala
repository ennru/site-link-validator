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

  case class Config(
      rootDir: Path,
      linkMappings: Map[String, String],
      // Local files that are 'ignored' are scanned for local
      // references but not for remote URLs
      ignoreFiles: Seq[String],
      ignorePrefixes: Seq[String])

  def findLinks(
      config: Config,
      reporter: ActorRef[Reporter.Messages],
      anchorValidator: ActorRef[AnchorValidator.Messages],
      urlTester: ActorRef[UrlTester.Messages],
      file: Path): List[LinkCollector.FileLocation] = {

    def collectAnchors(file: Path, anchors: List[Element], ids: List[Element]) = {
      val all = anchors.map(_.attr("name")).concat(ids.map(_.attr("id"))).filter(_.nonEmpty).toSet
      anchorValidator ! AnchorValidator.Anchor(file, all)
    }

    def checkLocalLink(file: Path, link: String): Option[LinkCollector.FileLocation] = {
      val (path, anchor) = splitLinkAnchor(link)
      if (path.nonEmpty) {
        val f =
          if (path.startsWith("/")) config.rootDir.resolve(path.drop(1))
          else file.getParent.resolve(path).normalize
        if (anchor.nonEmpty)
          anchorValidator ! AnchorValidator.Link(file, f, anchor)
        Some(LinkCollector.FileLocation(file, f))
      } else if (anchor.nonEmpty) {
        anchorValidator ! AnchorValidator.Link(file, file, anchor)
        None
      } else {
        None
      }
    }

    def applyLinkMappings(file: Path, link: String)(
        noMapping: => Option[LinkCollector.FileLocation]): Option[LinkCollector.FileLocation] = {
      config.linkMappings
        .collectFirst {
          case (prefix, path) if link.startsWith(prefix) =>
            (prefix, path)
        }
        .fold(noMapping) { case (prefix, path) =>
          val patchedLink = link.substring(prefix.length)
          checkLocalLink(file, path + patchedLink)
        }
    }

    def checkLinks(file: Path, linksInDocument: List[Element], followRemoteUrls: Boolean) =
      linksInDocument
        .flatMap { element =>
          val href = element.attr("abs:href")
          if (href.startsWith("http")) Option.when(followRemoteUrls)(AbsoluteLink(href))
          else {
            val href2 = element.attr("href")
            if (href2.startsWith("#")) Some(AnchorLink(href2.drop(1)))
            else Some(Link(href2))
          }
        }
        .flatMap {
          case AbsoluteLink(link) if config.ignorePrefixes.forall(prefix => !link.startsWith(prefix)) =>
            applyLinkMappings(file, link) {
              val (path, _) = splitLinkAnchor(link)
              urlTester ! UrlTester.Url(file, path)
              None
            }

          case AbsoluteLink(link) =>
            // ignored
            None

          case Link(link) =>
            applyLinkMappings(file, link) {
              checkLocalLink(file, link)
            }

          case AnchorLink(anchor) =>
            anchorValidator ! AnchorValidator.Link(file, file, anchor)
            None
        }

    def splitLinkAnchor(link: String) = {
      val p = link.indexOf('#')
      if (p == -1) {
        (link, "")
      } else {
        (link.substring(0, p), link.substring(p + 1))
      }

    }

    if (file.toFile.isFile) {
      val document = Jsoup.parse(file.toFile, "UTF-8", "/")
      val linksInDocument = document.select("a[href]").asScala.toList
      val followRemoteUrls = !config.ignoreFiles.map(config.rootDir.resolve).contains(file)
      val links = checkLinks(file, linksInDocument, followRemoteUrls)

      val anchors = document.select("a[name]").asScala.toList
      val ids = document.select("a[id]").asScala.toList
      collectAnchors(file, anchors, ids)
      links
    } else {
      reporter ! Reporter.FileErrored(file, new RuntimeException(s"$file is not a file"))
      List()
    }
  }
}
