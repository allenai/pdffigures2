package org.allenai.pdffigures2


object RegionClassifier extends Logging {

  /** Base class of classes that classify text */
  abstract private class TextClassifier() {

    /** @return return Some(true) iff `paragraph` appears to be figure text, Some(false) if it is
      *          body text, or None if unsure
      */
    def isBodyText(paragraph: Paragraph): Option[Boolean]
  }

  /** TextClassifier that can only confidently classify text as figure text */
  abstract private class FigureTextDetector extends TextClassifier {
    def isBodyText(paragraph: Paragraph): Option[Boolean] =
      if (isFigureText(paragraph)) Some(false) else None
    def isFigureText(paragraph: Paragraph): Boolean
  }

  /** Marks paragraphs that overlap graphical regions as figure text */
  private case class GraphicOverlaps(graphics: Seq[Box]) extends FigureTextDetector {
    override def isFigureText(paragraph: Paragraph): Boolean = {
      val b = paragraph.boundary
      graphics.exists(g => g.intersectArea(b) / b.area > 0.20)
    }
  }

  /** Marks vertical text paragraph as figure text */
  private case class VerticalText() extends FigureTextDetector {
    override def isFigureText(paragraph: Paragraph): Boolean = {
      paragraph.lines.forall(!_.isHorizontal)
    }
  }

  /** Marks text that appears to be a title as body text */
  private case class IsTitle(documentLayout: DocumentLayout) extends TextClassifier {
    override def isBodyText(paragraph: Paragraph): Option[Boolean] = {
      if (SectionTitleExtractor.isAlignedOrCentered(paragraph.boundary, documentLayout) &&
          SectionTitleExtractor.isTitleStartText(paragraph.lines.head) &&
          paragraph.lines.forall(l => SectionTitleExtractor.isTitleStyle(l, documentLayout))) {
        Some(true)
      } else {
        None
      }
    }
  }

  /** Mark wide spaced paragraphs as figure text */
  private case class Spacing(
    standardFontSize: Option[Double],
    averageWordSpacing: Double
  ) extends FigureTextDetector {
    override def isFigureText(paragraph: Paragraph): Boolean = {
      val wordSpaces = paragraph.lines.flatMap { line =>
        line.words
          .sliding(2)
          .map { words =>
            words.last.boundary.x1 - words.head.boundary.x2
          }
          .filter(_ > 0)
      }
      val wideSpacing = wordSpaces.nonEmpty && (
        wordSpaces.sum / wordSpaces.size.toDouble > averageWordSpacing + 5
      )
      val largeFont = if (standardFontSize.isDefined) {
        var total = 0
        var nonStandard = 0
        paragraph.lines.foreach { line =>
          line.words.foreach { word =>
            word.positions.foreach { pos =>
              total += 1
              if (pos.getFontSizeInPt - standardFontSize.get > 1) {
                nonStandard += 1
              }
            }
          }
        }
        nonStandard / total.toDouble > 0.95
      } else {
        false
      }
      !largeFont && wideSpacing
    }
  }

  /** Mark small font paragraphs as figure text */
  private case class SmallFont(standardFontSize: Option[Double]) extends FigureTextDetector {
    override def isFigureText(paragraph: Paragraph): Boolean = {
      val smallStandardFont = if (standardFontSize.isDefined) {
        var total = 0
        var small = 0
        paragraph.lines.foreach { line =>
          line.words.foreach { word =>
            word.positions.foreach { pos =>
              total += 1
              if (standardFontSize.get - pos.getFontSizeInPt > 0.1) {
                small += 1
              }
            }
          }
        }
        small / total.toDouble > 0.95
      } else {
        false
      }
      smallStandardFont
    }
  }

  /** Mark tall paragraphs of the paper's typical width as body text */
  private case class LineWidth(standardWidth: Option[Double]) extends TextClassifier {
    override def isBodyText(paragraph: Paragraph): Option[Boolean] = {
      if (paragraph.lines.size > 2 && standardWidth.isDefined &&
          Math.abs(paragraph.boundary.width - standardWidth.get) <
            DocumentLayout.LineWidthBucketSize) {
        Some(true)
      } else {
        None
      }
    }
  }

  /** Mark paragraphs that are small or not aligned to the margins as figure text, otherwise as body
    * text
    */
  private case class Margins(trustLeftMargin: Boolean, leftMargins: Map[Int, Double])
      extends TextClassifier {
    override def isBodyText(paragraph: Paragraph): Option[Boolean] = {
      val bodyText =
        if (!trustLeftMargin) {
          paragraph.boundary.area > 7000
        } else {
          val aligned = (
            leftMargins.getOrElse(Math.floor(paragraph.boundary.x1).toInt, 0.0) +
              leftMargins.getOrElse(Math.ceil(paragraph.boundary.x1).toInt, 0.0)
          ) > 0.18
          aligned && paragraph.boundary.area > 100
        }
      Some(bodyText)
    }
  }

  /** If `paragraph` intersects any caption in `captions` tries to split that paragraph up into
    * smaller paragraphs that no longer intersect any captions
    */
  private def splitAroundCaptions(
    paragraph: Paragraph,
    captions: Seq[CaptionParagraph],
    page: Int
  ): List[Paragraph] = {
    val intersects = captions.filter(c => paragraph.boundary.intersects(c.boundary, -2))
    if (intersects.nonEmpty) {
      val captionBoundaries = intersects.map(_.boundary)
      if (!paragraph.lines.forall(_.boundary.intersectsAny(captionBoundaries))) {
        logger.debug(s"Page: $page, Splitting paragraphs that overlap caption")
        var splitParagraphs = List[Paragraph]()
        var newLines = List(paragraph.lines.head)
        var oldLines = paragraph.lines.tail
        var newBox = paragraph.lines.head.boundary
        var nSplits = 0
        while (oldLines.nonEmpty) {
          val next = oldLines.head
          val combinedBox = next.boundary.container(newBox)
          if (combinedBox.intersectsAny(captionBoundaries)) {
            splitParagraphs = Paragraph(newLines.reverse, newBox) :: splitParagraphs
            nSplits += 1
            newLines = List(next)
            newBox = next.boundary
          } else {
            newBox = combinedBox
            newLines = next :: newLines
          }
          oldLines = oldLines.tail
        }
        Paragraph(newLines.reverse, newBox) :: splitParagraphs
      } else {
        logger.debug(s"Page: $page, Paragraph overlaps caption, but was unable to split it")
        List(paragraph)
      }
    } else {
      List(paragraph)
    }
  }

  /** Breaks a page into regions that are marked as potentially being part of a Figure or not
    *
    * Our approach is based on the idea that text insides figures is likely to be atypical of
    * text throughout the rest of the paper, so we apply a handful of heuristics to detect such
    * atypical text and mark it as figures text. See `TextClassifier` and its subclasses.
    *
    * We also try to detect captions that are contained within an image or inside a box, in which
    * we will mark the bordering region of that image/box as a non-figure region to ensure that
    * caption's figure region does not extend beyond that image/box.
    *
    * @param page page with captions, graphics, and formatting text identified
    * @param layout information about the document
    * @return page with the contents broken down into non figure elements and figure elements
    */
  def classifyRegions(page: PageWithCaptions, layout: DocumentLayout): PageWithBodyText = {
    val graphics = page.graphics
    var bodyText = List[Paragraph]()
    var otherText = List[Paragraph]()
    val paragraphs = page.paragraphs

    val separatedParagraphs =
      paragraphs.flatMap(p => splitAroundCaptions(p, page.captions, page.pageNumber))

    // 'Sieve' of heuristics, order is important since we will use the first one that fires
    val classifierSieve = Seq(
      GraphicOverlaps(page.graphics),
      VerticalText(),
      Spacing(layout.standardFontSize, layout.averageWordSpacing),
      LineWidth(layout.standardWidthBucketed),
      SmallFont(layout.standardFontSize),
      IsTitle(layout),
      Margins(layout.trustLeftMargin, layout.leftMargins)
    )

    separatedParagraphs.foreach { paragraph =>
      val classification = classifierSieve.view.flatMap(_.isBodyText(paragraph)).headOption
      // Use the first heuristic that fired, otherwise default to True
      val isBodyText = classification.isEmpty || classification.get
      if (isBodyText) {
        bodyText = paragraph :: bodyText
      } else {
        otherText = paragraph :: otherText
      }
    }

    // Try to detect graphic elements that that encompass a caption. If we find one we mark
    // the borders of that region as being a non-figure area
    val (figuresBoundingBoxGraphics, rest) = graphics.partition(
      graphicRegion =>
        page.captions.exists(
          c =>
            graphicRegion.contains(c.boundary)
              && graphicRegion.intersectArea(c.boundary) / graphicRegion.area < 0.50
        ) &&
          !paragraphs.exists(
            p =>
              !graphicRegion.contains(p.boundary) &&
                p.boundary.intersects(graphicRegion)
          )
    )
    val figureBoundingBoxes = figuresBoundingBoxGraphics.flatMap { box =>
      Seq(
        box.copy(x2 = box.x1),
        box.copy(x1 = box.x2),
        box.copy(y2 = box.y1),
        box.copy(y1 = box.y2)
      )
    }

    // Crop the surrounding region slightly, this a hacky way of making sure that in the case
    // a figures is bordered the returned region does not clip that border.
    val croppedFigureGraphics = figuresBoundingBoxGraphics.map(
      box => box.copy(x1 = box.x1 + 3, x2 = box.x2 - 3, y1 = box.y1 + 3, y2 = box.y2 - 3)
    )

    PageWithBodyText(
      page.pageNumber,
      page.classifiedText,
      page.captions,
      rest ++ croppedFigureGraphics,
      page.nonFigureGraphics ++ figureBoundingBoxes,
      bodyText,
      otherText
    )
  }
}
