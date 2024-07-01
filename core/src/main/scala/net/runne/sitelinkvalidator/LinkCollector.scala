package net.runne.sitelinkvalidator

import java.nio.file.{ Path, Paths }
import akka.actor.typed.ActorRef

import scala.annotation.tailrec

object LinkCollector {

  final case class FileLocation(origin: Path, file: Path)

  // Find all links and pass them to `anchorCollector` and `urlTester`
  def stream(
      root: FileLocation,
      htmlFileReaderConfig: HtmlFileReader.Config,
      reporter: ActorRef[Reporter.Messages],
      anchorCollector: ActorRef[AnchorValidator.Messages],
      urlTester: ActorRef[UrlTester.Messages]): Unit = {

    @tailrec
    def recurse(todo: List[FileLocation], done: Set[Path]): Unit = {
      todo match {
        case Nil =>
        case fileLocation :: xs =>
          val html = findHtml(fileLocation.file.normalize)
          if (done(html) || !html.toFile.isFile) {
            recurse(xs, done)
          } else {
            val links = HtmlFileReader.findLinks(htmlFileReaderConfig, reporter, anchorCollector, urlTester, html)
            recurse(xs ++ links, done + html)
          }
      }
    }
    recurse(List(root), Set.empty)
  }

  private def findHtml(p: Path) = {
    if (p.toFile.exists()) {
      if (p.toFile.isFile) p
      else {
        val index = p.resolve("index.html")
        if (p.toFile.isDirectory && index.toFile.isFile) index
        else p
      }
    } else {
      val p2 = Paths.get(p.toString + ".html")
      if (p2.toFile.isFile) p2
      else p
    }
  }
}
