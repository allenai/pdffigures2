package org.allenai.pdffigures2

import org.apache.pdfbox.pdmodel.font.PDFont

object CaptionBuilder extends Logging {

  private val AlignmentTolerance = 2.0
  private val GraphicIntersectTolerance = -2.0
  private val LargeParagraphNumberOfLines = 5.0

  /** How much space to allow between caption lines relative the median spacing in the document */
  private val MedianSpacingPadding = 0.2
  private val MaxAdditionalSpacing = 2.0

  /** When we add lines to captions because that line is to the right, not below the caption */
  private val LineContinuationMaxYDifference = 3.0
  private val LineContinuationMaxXDifference = 12.0

  /** note we need a large negative threshold since the height of lines can be dramatically
    * overestimated by PDFBox
    */
  private val MinYDistBetweenLines = -40

  private val LeftEdgeDifferenceTolerance = 30

  /** Return the PDFont of the line, if a PDFont is used for every character in the line,
    * otherwise None
    */
  private def getLineFont(line: Line): Option[PDFont] = {
    val fonts = line.words.flatMap(_.positions).map(_.getFont)
    if (fonts.tail.forall(_.equals(fonts.head))) {
      Some(fonts.head)
    } else {
      None
    }
  }

  /** Given a list of `CaptionStart` objects converts each one to a CaptionParagraph by identifying
    * all the text that is part of the caption the `CaptionStart` begins
    *
    * We assume the text is in reading order, so this task this amounts to deciding how many of
    * the lines following the caption start to include as part of the caption. In many cases just
    * adding lines as long as each line is reasonably close to the previous lines is effective,
    * however edges case arise when captions are widely spaces or jammed close to text that
    * follows them. In these cases we use a few additional heuristics:
    *
    * 1: If the caption is right justified we stop adding lines if the previous line breaks that
    *    justification
    * 2: The next line starts a new, large paragraph
    *
    * @param candidates Captions start to turn into captions
    * @param text Text and graphics on the page
    * @param medianLineSpacing median space between lines for this document
    */
  def buildCaptions(
    candidates: Seq[CaptionStart],
    text: PageWithGraphics,
    medianLineSpacing: Double
  ): PageWithCaptions = {
    if (candidates.nonEmpty) {
      val captionStartLocations = candidates.map(_.line.lineNumber)
      val captions = candidates.map(
        c =>
          buildCaption(
            c,
            captionStartLocations,
            text.paragraphs,
            text.graphics,
            medianLineSpacing + MedianSpacingPadding
          )
      )

      val textWithoutCaptions = Paragraph.removeSpans(
        captions.map(_.paragraph.span),
        text.paragraphs
      )

      PageWithCaptions(
        text.pageNumber,
        captions,
        text.graphics,
        text.nonFigureGraphics,
        textWithoutCaptions.toList,
        text.classifiedText
      )
    } else {
      PageWithCaptions(
        text.pageNumber,
        Seq(),
        text.graphics,
        text.nonFigureGraphics,
        text.paragraphs,
        text.classifiedText
      )
    }
  }

  /** An 'in-progress' Caption, keeps track of the font used in the Caption and whether all the
    * lines added so far are centered
    */
  private case class CaptionBuilder(
    lines: List[Line],
    boundary: Box,
    font: Option[PDFont],
    centered: Boolean
  ) {
    def lastLineRightAligned: Boolean = Math.abs(boundary.x2 - lines.last.boundary.x2) < 2.0
    def addLine(line: Line, newBoundary: Box, lineFont: Option[PDFont]): CaptionBuilder = {
      val newFont = (font, lineFont) match {
        case (Some(captionPDFont), Some(linePDFont)) if captionPDFont.equals(linePDFont) => lineFont
        case _ => None
      }
      val stillCentered = centered && Math.abs(line.boundary.xCenter - newBoundary.xCenter) < 2.0
      copy(lines = lines :+ line, boundary = newBoundary, centered = stillCentered, font = newFont)
    }
  }

  /* Again since PDFBox sometimes wildly overestimates the height of non-standard characters we can
     get extremely wrong estimates for the height of a caption. To resolve this we we prune each
     caption's height to the height of its first word since the first word of captions will be
     ASCII text and thus have a correctly estimated height.  */
  private def pruneCaptionParagraph(paragraph: Paragraph): Paragraph = {
    val prunedBB = paragraph.boundary.copy(y1 = paragraph.lines.head.words.head.boundary.y1)
    paragraph.copy(boundary = prunedBB)
  }

  /** Builds a single Caption from a CaptionStart */
  def buildCaption(
    candidate: CaptionStart,
    captionLocations: Seq[Int],
    paragraphs: Seq[Paragraph],
    graphicsLocations: Seq[Box],
    safeLineSpacing: Double
  ): CaptionParagraph = {

    val linesWithParagraphs = paragraphs.flatMap { paragraph =>
      paragraph.lines.map(line => (line, paragraph))
    }
    val linesStartingAtCaption = linesWithParagraphs.dropWhile {
      case (line, _) => line.lineNumber < candidate.line.lineNumber
    }
    require(linesStartingAtCaption.nonEmpty, "No lines at candidate's location")

    val startingLine = linesStartingAtCaption.head._1
    var currentCaption = CaptionBuilder(
      List(startingLine),
      startingLine.boundary,
      getLineFont(startingLine),
      true
    )

    // Avoid lines that intersect graphical regions, UNLESS that region includes the first line
    // of the caption (this usually means the caption is 'inside' a graphical region, like a
    // border or image with excess whitespace)
    val graphicsToAvoid = graphicsLocations.filter(
      !_.intersects(currentCaption.boundary, GraphicIntersectTolerance)
    )

    linesStartingAtCaption.tail.takeWhile {
      case (line, paragraph) =>
        val lineBB = line.boundary
        val currentBoundary = currentCaption.boundary
        val proposedBB = currentBoundary.container(lineBB)
        val yDist = lineBB.y1 - currentBoundary.y2
        val lineFont = getLineFont(line)
        val firstLine = currentCaption.lines.size == 1
        val firstLineAfterSingleLineHeader = firstLine && candidate.lineEnd
        val fontChange = lineFont.isDefined && currentCaption.font.isDefined &&
          !firstLineAfterSingleLineHeader && !lineFont.get.equals(currentCaption.font.get)

        val useLine =
          if (yDist < MinYDistBetweenLines ||
              yDist > safeLineSpacing + MaxAdditionalSpacing) {
            // Line is too far away
            false
          } else if (graphicsToAvoid.exists(
                       bb => bb.intersects(proposedBB, GraphicIntersectTolerance)
                     ) ||
                     captionLocations.contains(line.lineNumber)) {
            // The line either starts a new caption, or intersects some graphical element, don't use
            false
          } else if (yDist < LineContinuationMaxYDifference &&
                     currentCaption.lines.last.boundary.x2 - lineBB.x1 < LineContinuationMaxXDifference) {
            // Line is a continuation/to the right of the previous line
            true
          } else if (fontChange && !firstLineAfterSingleLineHeader) {
            // Line starts a new font, don't use
            false
          } else if (yDist < safeLineSpacing && yDist > 0 &&
                     Math.abs(currentBoundary.x1 - lineBB.x1) < AlignmentTolerance) {
            // Line is within the standard spacing and left-aligned to the caption, use
            true
          } else {
            // Last resort, add the line as long as no other formatting cues fire
            val centered = Math.abs(lineBB.xCenter - currentBoundary.xCenter) < AlignmentTolerance
            val overlapsHorizontal = lineBB.x1 < currentBoundary.x2 &&
              lineBB.x2 > currentBoundary.x1 &&
              (currentBoundary.x1 - proposedBB.x1 < LeftEdgeDifferenceTolerance
                || firstLineAfterSingleLineHeader)
            val startingLargeParagraph =
              line.lineNumber == paragraph.lines.head.lineNumber &&
                paragraph.lines.size >= LargeParagraphNumberOfLines
            val breaksJustification =
              !currentCaption.lastLineRightAligned && !(currentCaption.centered && centered)
            if (overlapsHorizontal && !startingLargeParagraph && !breaksJustification) {
              true
            } else {
              false
            }
          }
        if (useLine) currentCaption = currentCaption.addLine(line, proposedBB, lineFont)
        useLine
    }

    CaptionParagraph(
      candidate.name,
      candidate.figType,
      candidate.page,
      pruneCaptionParagraph(Paragraph(currentCaption.lines, currentCaption.boundary))
    )
  }
}
