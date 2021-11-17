package org.allenai.pdffigures2

import org.allenai.pdffigures2.FigureExtractor.OcredPdfException

import org.apache.pdfbox.pdmodel.PDDocument

object GraphicsExtractor extends Logging {

  private val GraphicClusteringTolerance = 2
  private val OcrGraphicMinSize = 2
  private val OcrPageBoundsTolerance = 70

  private val HeaderLineMinWidthPercent = 0.70
  private val HeaderLineMinHeight = 5
  private val HeaderLineMinY1 = 72
  private val HeaderLineMinDistToHeader = 18
  private val SecondHeaderMinDistToFirst = 36
  private val SecondHeaderMaxWidthDifference = 0.1

  private val MixedInGraphicMaxSize = 70
  private val MixedInGraphicContainsTolerance = 2

  /** Extract regions of the document that contain graphical, e.i. non-text, elements */
  def extractGraphics(
    doc: PDDocument,
    page: PageWithClassifiedText,
    allowOcr: Boolean,
    ignoreWhiteGraphics: Boolean,
    vLogger: Option[VisualLogger]
  ): PageWithGraphics = {
    val rawGraphics = extractRawGraphics(doc, page, allowOcr, ignoreWhiteGraphics)
    val pageBounds = Box.fromPDRect(doc.getPage(page.pageNumber).getCropBox)
    val (graphics, nonFigureGraphics) = preprocessGraphics(rawGraphics, page, pageBounds)
    logger.debug(s"Found ${graphics.size} graphic areas, ${graphics.size} after cleaning")

    if (vLogger.isDefined) vLogger.get.logGraphicCluster(page.pageNumber, rawGraphics, graphics)
    PageWithGraphics(
      page.pageNumber,
      page.paragraphs,
      graphics,
      nonFigureGraphics,
      page.classifiedText
    )
  }

  /* Extract graphical regions from a page */
  private def extractRawGraphics(
    doc: PDDocument,
    textPage: PageWithClassifiedText,
    allowOcr: Boolean,
    ignoreWhiteGraphics: Boolean
  ): List[Box] = {
    val page = textPage.pageNumber
    val bounds = Box.fromPDRect(doc.getPage(page).getCropBox)
    val graphics = GraphicBBDetector.findGraphicBB(doc.getPage(page), ignoreWhiteGraphics)
    if (graphics.exists(_.contains(bounds, 1)) ||
        graphics.size == 1 && graphics.head.contains(bounds, OcrPageBoundsTolerance)) {
      if (allowOcr) {
        logger.debug(s"Page $page is an image, falling back to CC graphic detection")
        val rasterCCs = FindGraphicsRaster.findCCBoundingBoxes(
          doc,
          page,
          (textPage.classifiedText.allText ++ textPage.paragraphs).map(_.boundary)
        )
        rasterCCs.filter(_.area > OcrGraphicMinSize) // Clean up noise/trailing character pixels
      } else {
        logger.debug(s"Page $page is an image and allow OCR is false, giving up")
        throw new OcredPdfException(s"Page $page is an image and allow OCR is turned off")
      }
    } else {
      graphics
    }
  }

  /** Given 'raw' bounding regions of graphics on a page, cleans and clusters the regions and tries
    * to detect graphics that are not part of figure.
    *
    * @return (nonFigureGraphicRegion, otherGraphicRegion
    */
  private def preprocessGraphics(
    graphics: List[Box],
    text: PageWithClassifiedText,
    bounds: Box
  ): (Seq[Box], Seq[Box]) = {

    // Try to detect if the page has "header line(s)", we either look for a line near the
    // head text, if any exist, else for a wide line above everything else on the page
    val pageHeader = text.classifiedText.pageHeaders
    val (wideLines, nonLines) = graphics.partition(
      g =>
        g.height < HeaderLineMinHeight && g.y1 < HeaderLineMinY1 &&
          g.width / bounds.width > HeaderLineMinWidthPercent
    )
    val sortedWideLines = wideLines.sortBy(_.y1)
    val headerLine = if (sortedWideLines.isEmpty) {
      false
    } else if (pageHeader.nonEmpty) {
      val headerEnd = pageHeader.minBy(_.boundary.y1).boundary.yCenter
      Math.abs(sortedWideLines.head.y2 - headerEnd) < HeaderLineMinDistToHeader
    } else {
      val minTextElement = text.paragraphs.map(_.boundary.y1).min
      sortedWideLines.size == 1 && sortedWideLines.head.y1 < minTextElement
    }

    val (headerLines, nonHeaderGraphic) = if (headerLine) {
      val secondHeaderLine = if (sortedWideLines.size > 1) {
        val secondLine = sortedWideLines(1)
        secondLine.y1 - sortedWideLines.head.y1 < SecondHeaderMinDistToFirst &&
        Math.abs(sortedWideLines.head.x1 - secondLine.x1) < SecondHeaderMaxWidthDifference &&
        Math.abs(sortedWideLines.head.x2 - secondLine.x2) < SecondHeaderMaxWidthDifference
      } else {
        false
      }
      if (secondHeaderLine) {
        logger.debug("Two header lines detected")
      } else {
        logger.debug("Header line detected")
      }
      val numLines = if (secondHeaderLine) 2 else 1
      (sortedWideLines.take(numLines), sortedWideLines.drop(numLines) ++ nonLines)
    } else {
      (Seq(), graphics)
    }

    // Look for images boxes that hugs an entire side of the PDF, this usually indicates some kind
    // of colored / "side panel" or index inside the PDF
    val (sidePanel, graphicsToMerge) =
      nonHeaderGraphic.partition(
        b =>
          Math.abs(b.height - bounds.height) < 1 &&
            (b.x1 < 1 || Math.abs(b.x2 - bounds.x2) < 1)
      )

    val merged = Box.mergeBoxes(graphicsToMerge, GraphicClusteringTolerance) // Cluster
    // Filter out graphics that look like they are 'mixed in' in with text, could be
    // part of an equation or an underline below some word
    val processedGraphics = merged.filterNot { box =>
      box.area < MixedInGraphicMaxSize && text.paragraphs.exists(
        paragraph => paragraph.boundary.contains(box, MixedInGraphicContainsTolerance)
      )
    }
    (processedGraphics, headerLines ++ sidePanel)
  }
}
