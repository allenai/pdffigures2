package org.allenai.pdffigures2

import org.allenai.common.Config._
import org.allenai.common.Logging
import org.allenai.pdffigures2.FigureExtractor.{
  DocumentWithRasterizedFigures,
  DocumentContent,
  Document
}
import org.allenai.pdffigures2.SectionedTextBuilder.{ PdfText, DocumentSection }

import com.typesafe.config.ConfigFactory
import org.apache.pdfbox.pdmodel.PDDocument

import java.io.InputStream

case class FigureExtractor(
    allowOcr: Boolean,
    ignoreWhiteGraphics: Boolean,
    detectSectionTitlesFirst: Boolean,
    rebuildParagraphs: Boolean,
    cleanRasterizedFigureRegions: Boolean
) extends Logging {
  def getFigures(doc: PDDocument, pages: Option[Seq[Int]] = None,
    visualLogger: Option[VisualLogger] = None): Iterable[Figure] = {
    parseDocument(doc, pages, visualLogger).figures
  }

  def getRasterizedFigures(doc: PDDocument, dpi: Int, pages: Option[Seq[Int]] = None,
    visualLogger: Option[VisualLogger] = None): Iterable[RasterizedFigure] = {
    val content = parseDocument(doc, pages, visualLogger)
    content.pagesWithFigures.flatMap(page =>
      FigureRenderer.rasterizeFigures(doc, page, dpi, cleanRasterizedFigureRegions, visualLogger))
  }

  def getFiguresWithErrors(doc: PDDocument, pages: Option[Seq[Int]] = None,
    visualLogger: Option[VisualLogger] = None): FiguresInDocument = {
    val content = parseDocument(doc, pages, visualLogger)
    FiguresInDocument(content.figures, content.failedCaptions)
  }

  def getRasterizedFiguresWithErrors(doc: PDDocument, dpi: Int, pages: Option[Seq[Int]] = None,
    visualLogger: Option[VisualLogger] = None): RasterizedFiguresInDocument = {
    val content = parseDocument(doc, pages, visualLogger)
    val rasterizedFigures = content.pagesWithFigures.flatMap(page =>
      FigureRenderer.rasterizeFigures(doc, page, dpi, cleanRasterizedFigureRegions, visualLogger))
    RasterizedFiguresInDocument(rasterizedFigures, content.failedCaptions)
  }

  def getFiguresWithText(doc: PDDocument, pages: Option[Seq[Int]] = None,
    visualLogger: Option[VisualLogger] = None): Document = {
    val content = parseDocument(doc, pages, visualLogger)
    val abstractText = getAbstract(content)
    val sections = getSections(content)
    if (visualLogger.isDefined) {
      visualLogger.get.logSections(sections, pages)
    }
    Document(content.figures, abstractText, sections)
  }

  def getRasterizedFiguresWithText(doc: PDDocument, dpi: Int, pages: Option[Seq[Int]] = None,
    visualLogger: Option[VisualLogger] = None): DocumentWithRasterizedFigures = {
    val content = parseDocument(doc, pages, visualLogger)
    val abstractText = getAbstract(content)
    val sections = getSections(content)
    if (visualLogger.isDefined) {
      visualLogger.get.logSections(sections, pages)
    }
    val rasterizedFigures = content.pagesWithFigures.flatMap(page =>
      FigureRenderer.rasterizeFigures(doc, page, dpi, cleanRasterizedFigureRegions, visualLogger))
    DocumentWithRasterizedFigures(rasterizedFigures, abstractText, sections)
  }

  private def getSections(content: DocumentContent): Seq[DocumentSection] = {
    if (content.layout.isEmpty) {
      content.pagesWithoutFigures.map(p =>
        DocumentSection(None, p.paragraphs.map(PdfText(_, p.pageNumber))))
    } else {
      val documentLayout = content.layout.get
      val text = if (!detectSectionTitlesFirst) {
        SectionTitleExtractor.stripSectionTitlesFromTextPage(content.pages, documentLayout)
      } else {
        content.pages
      }
      SectionedTextBuilder.buildSectionedText(text.toList)
    }
  }

  private def getAbstract(documentContent: DocumentContent): Option[PdfText] = {
    val pageWithAbstract = documentContent.pages.find(_.classifiedText.abstractText.nonEmpty)
    pageWithAbstract match {
      case None => None
      case Some(page) => Some(PdfText(
        Paragraph(page.classifiedText.abstractText.flatMap(_.lines).toList),
        page.pageNumber
      ))
    }
  }

  /* Runs the full processing pipeline and returns the figures and intermediate output */
  private def parseDocument(doc: PDDocument, pages: Option[Seq[Int]],
    visualLogger: Option[VisualLogger]): DocumentContent = {
    val pagesWithText = TextExtractor.extractText(doc)
    val pagesWithFormattingText = FormattingTextExtractor.extractFormattingText(pagesWithText)
    val documentLayoutOption = DocumentLayout(pagesWithFormattingText)
    if (documentLayoutOption.isEmpty) {
      logger.debug("Not enough information to build DocumentLayout, not detecting figures")
      DocumentContent(None, Seq(), pagesWithFormattingText)
    } else {
      val documentLayout = documentLayoutOption.get
      val rebuiltParagraphs = if (rebuildParagraphs) {
        pagesWithFormattingText.map(p => ParagraphRebuilder.rebuildParagraphs(p, documentLayout))
      } else {
        pagesWithFormattingText
      }
      val withSections = if (detectSectionTitlesFirst) {
        SectionTitleExtractor.stripSectionTitlesFromTextPage(rebuiltParagraphs, documentLayout)
      } else {
        rebuiltParagraphs
      }
      val captionStarts = CaptionDetector.findCaptions(withSections, documentLayout)
      val captionStartsFiltered = pages match {
        case Some(pagesToUse) => captionStarts.filter(c => pagesToUse.contains(c.page))
        case None => captionStarts
      }
      val candidatesByPage = captionStartsFiltered.groupBy(_.page)
      val pagesWithFigures = candidatesByPage.map {
        case (pageNum, pageCandidates) =>
          if (Thread.interrupted()) throw new InterruptedException()
          logger.debug(s"On page $pageNum")
          val pageText = withSections(pageNum)
          val pageWithGraphics =
            GraphicsExtractor.extractGraphics(doc, pageText,
              allowOcr, ignoreWhiteGraphics, visualLogger)
          if (visualLogger.isDefined) visualLogger.get.logExtractions(pageWithGraphics)
          val pageWithCaptions = CaptionBuilder.buildCaptions(
            pageCandidates,
            pageWithGraphics, documentLayout.medianLineSpacing
          )
          if (visualLogger.isDefined) visualLogger.get.logPagesWithCaption(pageWithCaptions)
          val pageWithRegions = RegionClassifier.classifyRegions(pageWithCaptions, documentLayout)
          if (visualLogger.isDefined) visualLogger.get.logRegions(pageWithRegions)
          val pageWithFigures = FigureDetector.locatedFigures(
            pageWithRegions, documentLayout, visualLogger
          )

          if (visualLogger.isDefined) visualLogger.get.logFigures(
            pageWithFigures.pageNumber,
            pageWithFigures.figures
          )
          pageWithFigures
      }.toSeq
      val otherPages =
        withSections.filter(p => pagesWithFigures.forall(_.pageNumber != p.pageNumber))
      DocumentContent(Some(documentLayout), pagesWithFigures, otherPages)
    }
  }
}

object FigureExtractor {
  /** Fully parsed document, including non-figure information produced by intermediate steps.
    * This is a "physical" page-based representation of a PDF, as opposed to a logical
    * section-based representation of the paper that the Document class implementation
    */
  case class DocumentContent(
      layout: Option[DocumentLayout],
      pagesWithFigures: Seq[PageWithFigures], pagesWithoutFigures: Seq[PageWithClassifiedText]
  ) {
    val pages = (pagesWithFigures ++ pagesWithoutFigures).sortBy(_.pageNumber)
    def figures = pagesWithFigures.flatMap(_.figures)
    def failedCaptions = pagesWithFigures.flatMap(_.failedCaptions)
    require(pages.head.pageNumber == 0, "Must start with page number 0")
    require(pages.sliding(2).forall(pages => pages.size == 1 ||
      pages.head.pageNumber + 1 == pages.last.pageNumber), "Pages number must be consecutive")
  }

  /** Document with figures extracted and text broken up into sections. A logical, section-based
    * representation of a paper as opposed to a page-based representation of the PDF that
    * DocumentContent implements
    */
  case class Document(
    figures: Seq[Figure],
    abstractText: Option[PdfText],
    sections: Seq[DocumentSection]
  )

  object Document {
    private val figureExtractor = new FigureExtractor(true, true, true, true, true)

    def fromInputStream(is: InputStream): Document =
      fromPDDocument(PDDocument.load(is))

    def fromPDDocument(pdDocument: PDDocument) =
      figureExtractor.getFiguresWithText(pdDocument)
  }

  /** Document with figures rasterized */
  case class DocumentWithRasterizedFigures(
    figures: Seq[RasterizedFigure], abstractText: Option[PdfText], sections: Seq[DocumentSection]
  )

  /** Document with figures saved to disk */
  case class DocumentWithSavedFigures(figures: Seq[SavedFigure], abstractText: Option[PdfText],
    sections: Seq[DocumentSection])

  /** Thrown if we detect an OCR PDF and `allowOcr` is set to false */
  class OcredPdfException(message: String = null, cause: Throwable = null)
    extends RuntimeException(message, cause)

  val conf = ConfigFactory.load()
  val allowOcr = conf[Boolean]("allowOcr")
  val detectSectionTitlesFirst = conf[Boolean]("detectSectionTitlesFirst")
  val rebuildParagraphs = conf[Boolean]("rebuildParagraphs")
  val ignoreWhiteGraphics = conf[Boolean]("ignoreWhiteGraphics")
  val cleanRasterizedFigureRegions = conf[Boolean]("cleanRasterizedFigureRegions")

  def apply(): FigureExtractor = {
    new FigureExtractor(
      allowOcr = allowOcr,
      ignoreWhiteGraphics = ignoreWhiteGraphics,
      detectSectionTitlesFirst = detectSectionTitlesFirst,
      rebuildParagraphs = rebuildParagraphs,
      cleanRasterizedFigureRegions = cleanRasterizedFigureRegions
    )
  }
}
