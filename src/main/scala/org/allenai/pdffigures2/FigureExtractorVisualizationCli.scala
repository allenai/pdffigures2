package org.allenai.pdffigures2

import org.apache.pdfbox.io.MemoryUsageSetting

import org.apache.pdfbox.pdmodel.PDDocument

import java.io.File

/** CLI to create visualization of the processing pipeline for a single PDF */
object FigureExtractorVisualizationCli extends Logging {

  case class CliConfig(
    input: Option[File] = None,
    pages: Option[Seq[Int]] = None,
    displayDpi: Int = 55,
    showAllSteps: Boolean = false,
    showRegions: Boolean = false,
    showExtractions: Boolean = false,
    showGraphicsClustering: Boolean = false,
    showCaptions: Boolean = false,
    showSections: Boolean = false,
    showCleanedFigureRegions: Boolean = false
  )

  val Parser = new scopt.OptionParser[CliConfig]("figure-extractor-visualize") {
    head("figure-extractor-visualize")
    arg[String]("<input>") required () action { (i, c) =>
      c.copy(input = Some(new File(i)))
    } validate { x =>
      val f = new File(x)
      if (!f.exists || f.isDirectory || !x.endsWith(".pdf")) {
        failure(s"File $x is not a PDF file")
      } else {
        success
      }
    } text "input PDF file"
    opt[Unit]('s', "show-steps") action { (_, c) =>
      c.copy(showAllSteps = true)
    } text "Show all intermediate steps"
    opt[Unit]('g', "show-graphic-clustering") action { (_, c) =>
      c.copy(showGraphicsClustering = true)
    } text "Show graphical elements found and how they were clustered"
    opt[Unit]('x', "show-cleaned-figure-regions") action { (_, c) =>
      c.copy(showCleanedFigureRegions = true)
    } text "Shows figure regions after being post-processed using the" +
      " rasterized PDF at the given DPI"
    opt[Unit]('e', "show-extractions") action { (_, c) =>
      c.copy(showExtractions = true)
    } text "Show the bounding boxes of the text and graphics that were extracted"
    opt[Unit]('r', "show-regions") action { (_, c) =>
      c.copy(showRegions = true)
    } text "Show the different regions the PDF was broken into"
    opt[Unit]('c', "show-captions") action { (_, c) =>
      c.copy(showCaptions = true)
    } text "Show the location of the captions"
    opt[Unit]('t', "show-sections") action { (_, c) =>
      c.copy(showSections = true)
    } text "Show the location of sections and paragraphs"
    opt[Int]('d', "display-dpi") action { (dpi, c) =>
      c.copy(displayDpi = dpi)
    } validate { dpi =>
      if (dpi > 0) success else failure("DPI must > 0")
    } text "DPI to display figures at (default 55)"
    opt[Seq[Int]]('p', "pages") action { (pages, c) =>
      c.copy(pages = Some(pages))
    } text "Pages to extract from (defaults to all), 1 is the first page" validate { x =>
      if (x.exists(_ <= 0)) failure("A page was <= 0") else success
    }
  }

  def run(config: CliConfig): Unit = {
    val inputFile = config.input.get
    val pages = config.pages match {
      case Some(pagesToScan) => Some(pagesToScan.map(_ - 1)) // Switch to 0 based index
      case None => None
    }
    val vLogger = new VisualLogger(
      true, // Always show the figures
      config.showAllSteps || config.showExtractions,
      config.showAllSteps || config.showGraphicsClustering,
      config.showAllSteps || config.showCaptions,
      config.showAllSteps || config.showRegions,
      config.showSections,
      config.showCleanedFigureRegions
    )
    val doc = PDDocument.load(inputFile)
    logger.info(s"Loading ${inputFile.getName}")

    logger.info(s"Extracting figures from ${inputFile.getName}")
    // Logs all the extraction steps to `vLogger`
    if (config.showSections) {
      if (config.showCleanedFigureRegions) {
        FigureExtractor()
          .getRasterizedFiguresWithText(
            doc,
            config.displayDpi,
            pages,
            Some(vLogger)
          )
          .figures
      } else {
        FigureExtractor().getFiguresWithText(doc, pages, Some(vLogger)).figures
      }
    } else {
      if (config.showCleanedFigureRegions) {
        FigureExtractor().getRasterizedFigures(
          doc,
          config.displayDpi,
          pages,
          Some(vLogger)
        )
      } else {
        FigureExtractor().getFigures(doc, pages, Some(vLogger))
      }
    }
    logger.info(s"Displaying figures")
    vLogger.displayVisualLog(doc, config.displayDpi)
    doc.close()
    logger.info(s"Finished")
  }

  def main(args: Array[String]): Unit = {
    Parser.parse(args, CliConfig()) match {
      case Some(config) => run(config)
      case None => System.exit(1)
    }
  }
}
