package org.allenai.pdffigures2

object ParagraphRebuilder {

  private val MaxIndentSize = 20
  private val MinIndentSize = 3
  private val MinLineContinuationXDist = 15
  private val MinLineContinuationVerticalOverlap = 0.8
  private val TextAlignmentTolerance = 2.0
  private val LineSpacingPadding = 0.5

  def rebuildParagraphs(
    page: PageWithClassifiedText,
    documentLayout: DocumentLayout
  ): PageWithClassifiedText = {
    val newParagraphs = mergeSortedParagraphs(page.paragraphs, documentLayout.medianLineSpacing)
    page.copy(paragraphs = newParagraphs)
  }

  /** Merges paragraphs in `paragraphs` that are appear to part of the same paragraph, judging by
    * `medianLineSpacing`
    *
    * @param paragraphs to merge
    * @param medianLineSpacing median line spacing of the document
    */
  def mergeParagraphs(paragraphs: Seq[Paragraph], medianLineSpacing: Double): List[Paragraph] =
    mergeSortedParagraphs(paragraphs.sorted, medianLineSpacing)

  private def mergeSortedParagraphs(
    paragraphs: Seq[Paragraph],
    medianLineSpacing: Double
  ): List[Paragraph] = {
    if (paragraphs.isEmpty) {
      List()
    } else {
      var newParagraphs = List[Paragraph]()
      var onParagraph: Paragraph = paragraphs.head
      paragraphs.tail.foreach { paragraph =>
        val mergeParagraph = {
          val continuesReadingOrder = onParagraph.span.end == paragraph.span.start - 1
          val curBB = onParagraph.boundary
          val yDist = paragraph.boundary.y1 - curBB.y2
          val belowLine = yDist > -4 && yDist < medianLineSpacing + LineSpacingPadding &&
            curBB.horizontallyAligned(paragraph.boundary, 0)

          val leftAligned = Math.abs(curBB.x1 - paragraph.boundary.x1) < TextAlignmentTolerance
          val rightAligned = Math.abs(curBB.x2 - paragraph.boundary.x2) < TextAlignmentTolerance
          val indent = paragraph.lines.head.boundary.x1 - curBB.x1
          val indented = indent > MinIndentSize && indent < MaxIndentSize
          val prevLineIndented = -indent > MinIndentSize && -indent < MaxIndentSize

          val xDist = paragraph.boundary.x1 - curBB.x2
          val verticalOverlap = Math.min(curBB.y2, paragraph.boundary.y2) -
            Math.max(curBB.y1, paragraph.boundary.y1)
          val verticallyAligned = xDist < MinLineContinuationXDist &&
            verticalOverlap / Math.min(paragraph.boundary.height, curBB.height) >
              MinLineContinuationVerticalOverlap

          continuesReadingOrder && (verticallyAligned ||
          belowLine && ((!indented && leftAligned) || (prevLineIndented && rightAligned)))
        }
        if (mergeParagraph) {
          onParagraph = Paragraph(onParagraph.lines ++ paragraph.lines)
        } else {
          newParagraphs = newParagraphs :+ onParagraph
          onParagraph = paragraph
        }
      }
      newParagraphs :+ onParagraph
    }
  }
}
