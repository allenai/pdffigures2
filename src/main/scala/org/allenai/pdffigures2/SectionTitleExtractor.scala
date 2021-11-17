package org.allenai.pdffigures2

import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.text.TextPosition

object SectionTitleExtractor extends Logging {

  private val TextAlignmentTolerance = 2.0
  private val MaxNonCapitalizedLargeWords = 2
  private val PruneNonPrefixedSections = 0.7
  private val MinSharedMargin = 0.1

  private val NumberRegex = """^[1-9][0-9]*(.[1-9][0-9]*)*.?$""".r
  private val LetterNumberRegex = """^[A-Z](.|[1-9]*.?)$""".r
  private val RomanNumeralsRegex = """^[IVX]+.?$""".r
  private val AppendixRegex = "^(?i)appendix.?$".r

  /** @return whether `line` is "prefixed", meaning it starts with some kind of numbering or list
    *         indicator, followed by the text of the title
    */
  def isPrefixed(line: Line): Boolean = {
    if (line.words.size == 1) {
      false
    } else {
      val firstWordText = line.words.head.text

      RegexWithTimeout.matcher(NumberRegex.pattern, firstWordText).matches() ||
      RegexWithTimeout.matcher(RomanNumeralsRegex.pattern, firstWordText).matches() ||
      RegexWithTimeout.matcher(LetterNumberRegex.pattern, firstWordText).matches() ||
      RegexWithTimeout.matcher(AppendixRegex.pattern, firstWordText).matches()
    }
  }

  /** @return whether `line` has text that could indicate it starts a section title */
  def isTitleStartText(line: Line): Boolean = {
    val text = line.text
    text.length > 1 && (Character.isUpperCase(text(0)) || isPrefixed(line))
  }

  /** @return whether `region` is left aligned or centered to the body text */
  def isAlignedOrCentered(region: Box, layout: DocumentLayout): Boolean = {
    val isCenter = if (layout.standardWidthBucketed.isDefined) {
      val x1 = region.xCenter - layout.standardWidthBucketed.get / TextAlignmentTolerance
      layout.leftMargins.getOrElse(Math.ceil(x1).toInt, 0.0) +
        layout.leftMargins.getOrElse(Math.floor(x1).toInt, 0.0) > MinSharedMargin
    } else {
      false
    }
    val leftAligned = if (layout.trustLeftMargin) {
      val x1 = region.x1
      layout.leftMargins.getOrElse(Math.ceil(x1).toInt, 0.0) +
        layout.leftMargins.getOrElse(Math.floor(x1).toInt, 0.0) > MinSharedMargin
    } else {
      false
    }
    leftAligned || isCenter
  }

  /** @return whether `line` has some formatting indicators that indicate it is a title */
  def isTitleStyle(line: Line, layout: DocumentLayout): Boolean = {
    val numChars = line.words.map(_.positions.size).sum
    val nonStandardFont = line.words.flatMap(_.positions).count { pos =>
      layout.fontCounts.getOrElse(pos.getFont, 0.0) < 0.1
    }

    // We check the size 'layout.standardFontSize' because on a few PDFs the standard font size can
    // estimated as being very large (ex. 99pt, 100pt), seemingly because PDFBox will sometimes
    // marks large amounts of text as having this font size
    val trustFontSize = layout.standardFontSize.nonEmpty && layout.standardFontSize.get < 20
    val smallFont = trustFontSize && line.words.flatMap(_.positions).count { pos =>
      pos.getFontSizeInPt < layout.standardFontSize.get - 1.0
    } > numChars / 2

    // Characters should all be the same font/font size, expect we want to give exceptions to some
    // unicode characters (like mathematical systems). So we only check larger words without
    // unicode characters.
    val asciiChars = line.words
      .filter(_.positions.size > 2)
      .filter(_.positions.forall(isNormalText))
      .flatMap(_.positions)
    val sameFont = asciiChars.nonEmpty &&
      asciiChars.tail.forall(_.getFont.equals(asciiChars.head.getFont))

    val isAllCaps = !line.text.exists(Character.isLowerCase)
    sameFont && (!smallFont && nonStandardFont > numChars / 2 || isAllCaps)
  }

  /** @return whether `pos` is a funky unicode characters */
  private def isNormalText(pos: TextPosition): Boolean =
    pos.getUnicode.length == 1 && (pos.getUnicode.head < 128 || pos.getUnicode == "ﬁ")

  /** @return whether `line` appears to be an equation, good precision but poor recall */
  private def isEquation(line: Line): Boolean = {
    val nonStandardChar = line.words.flatMap(_.positions).count { position =>
      position.getUnicode.length > 1 ||
      !Character.isLetter(position.getUnicode.head) ||
      position.getUnicode.head > 128
    }
    val numChars = line.words.map(_.positions.size).sum
    !isPrefixed(line) && nonStandardChar > 3 && nonStandardChar > numChars * 0.4
  }

  private val ListRegex = """^([1-9][0-9]*|[IVX]+)(.|:)?""".r

  /** @return whether `line` appears to part of list, like 'Definition 1', theses lists often have
    * formatting characteristics similar to headers so we filter for them based on content
    */
  def isList(line: Line): Boolean = {
    line.words.size > 1 &&
    Character.isUpperCase(line.words.head.text.head) &&
    ListRegex.findFirstIn(line.words.tail.head.text).nonEmpty
  }

  // Last resort try to filter on some simple text based heuristics
  private val BlackList = Seq(
    """\b[wW]e\b""".r,
    """^Proceedings of""".r
  )
  private def isCompleteTitle(sectionTitle: SectionTitle): Boolean = {
    val text = sectionTitle.toParagraph.text
    if (BlackList.exists(r => r.findFirstIn(text).nonEmpty)) {
      logger.trace(s"Blacklist removed $text as a header")
      false
    } else if (sectionTitle.lines.size > 3) {
      false
    } else if (sectionTitle.isPrefixed) {
      true
    } else {
      // Try to detect ordinary sentences by the presence of many uncapitalized words
      val words = sectionTitle.lines.flatMap(_.words)
      val largeNormalWords = words
        .filter(w => w.positions.size > 3 && Character.isLetter(w.text.head))
        .filter(_.positions.forall(isNormalText))
      if (!sectionTitle.isPrefixed && largeNormalWords.size > 3) {
        val numNonCapitalized = largeNormalWords.count { w =>
          !Character.isUpperCase(w.positions.head.getUnicode.head)
        }
        numNonCapitalized < MaxNonCapitalizedLargeWords
      } else {
        true
      }
    }
  }

  private def isBeneath(upper: Box, lower: Box): Boolean =
    upper.horizontallyAligned(lower, 50) && upper.y2 - 5 < lower.y2

  private def isFarFromPreviousLine(
    line: Line,
    prevLine: Option[Line],
    layout: DocumentLayout
  ): Boolean = {
    if (prevLine.isDefined) {
      // Only do this check if the height is reasonable to avoid getting thrown by PDFBox widely
      // overestimating the line height
      val largeVerticalSpace = prevLine.get.boundary.height > 40 ||
        line.boundary.y1 - prevLine.get.boundary.y2 > layout.medianLineSpacing + 0.1
      largeVerticalSpace
    } else {
      true
    }
  }

  private def isLineBeginningSection(
    line: Line,
    current: SectionTitle,
    layout: DocumentLayout
  ): Boolean = {
    val yDist = line.boundary.y1 - current.boundary.y2
    val close = yDist < layout.medianLineSpacing
    val rightAndLeftAligned =
      Math.abs(current.boundary.x1 - line.boundary.x1) < TextAlignmentTolerance &&
        Math.abs(current.boundary.x2 - line.boundary.x2) < TextAlignmentTolerance
    val largeFont = current.fontSize >
      layout.standardFontSize.getOrElse(layout.averageFontSize + 2.0)
    (line.words.size > 3 || Character.isUpperCase(line.words.head.text.head)) &&
    current.lines.last.text.last != '-' && (largeFont || !close || !rightAndLeftAligned)
  }

  private object SectionTitle {

    def build(line: Line): SectionTitle = {
      val fountCounts = line.words.flatMap(w => w.positions.map(_.getFont)).groupBy(identity)
      val mostCommonFont = fountCounts.mapValues(_.size).maxBy(_._2)._1
      val fontSizes = line.words.flatMap(w => w.positions.map(_.getFontSizeInPt))
      val medianFontSize = fontSizes.sorted.drop(fontSizes.size / 2).head
      SectionTitle(List(line), line.boundary, isPrefixed(line), mostCommonFont, medianFontSize)
    }
  }

  /** An "in progress" SectionTitle. `lines` contains the first line in the section title, but
    * there might be additional lines to add to this SectionTitle that have not be identified yet.
    */
  private case class SectionTitle(
    lines: List[Line],
    boundary: Box,
    isPrefixed: Boolean,
    font: PDFont,
    fontSize: Double
  ) {

    /** Is `other` a continuation of this or not. Used to merge multi-line section titles */
    def isMatch(other: SectionTitle): Boolean = {
      val hyphenated = lines.last.text.last == '-'
      val yDist = other.boundary.y1 - boundary.y2
      val close = yDist > -2 && yDist < 10
      val centered = Math.abs(other.boundary.xCenter - boundary.xCenter) < TextAlignmentTolerance
      val leftAligned = Math.abs(other.boundary.x1 - boundary.x1) < TextAlignmentTolerance
      val (secondWordLeftAligned, secondWordCentered) = if (lines.head.words.size > 1) {
        val secondWord = lines.head.words.tail.head
        val secondWordLeftAligned =
          Math.abs(other.boundary.x1 - secondWord.boundary.x1) < TextAlignmentTolerance
        val secondWordCenter = (lines.head.boundary.x2 + secondWord.boundary.x1) / 2.0
        val secondWordCentered =
          Math.abs(other.boundary.xCenter - secondWordCenter) < TextAlignmentTolerance
        (secondWordLeftAligned, secondWordCentered)
      } else {
        (false, false)
      }
      val sameFont = other.font.equals(font)
      val sameFontSize = other.fontSize == fontSize
      !other.isPrefixed && close && sameFontSize && sameFont &&
      (leftAligned || centered || secondWordLeftAligned || secondWordCentered || hyphenated)
    }

    def addLine(line: Line): SectionTitle =
      copy(lines = lines :+ line, boundary = Box.container(Seq(boundary, line.boundary)))

    def toParagraph: Paragraph = Paragraph(lines, boundary)
  }

  def stripSectionTitlesFromTextPage(
    pages: Seq[ClassifiedPage],
    layout: DocumentLayout
  ): Seq[PageWithClassifiedText] = {
    val (strippedTextPages, sectionHeaders) =
      stripSectionTitlesFromSortedParagraphs(pages.map(_.paragraphs), layout).unzip
    (pages, strippedTextPages, sectionHeaders).zipped.map {
      case (page, strippedText, pageSectionHeaders) =>
        PageWithClassifiedText(
          page.pageNumber,
          strippedText.toList,
          page.classifiedText.copy(sectionTitles = pageSectionHeaders)
        )
    }
  }

  def stripSectionTitles(
    pages: Seq[Seq[Paragraph]],
    layout: DocumentLayout
  ): Seq[(Seq[Paragraph], Seq[Paragraph])] = {
    stripSectionTitlesFromSortedParagraphs(pages.map(_.sorted), layout)
  }

  /** Try to detect if a paper has consistently numbered its sections, and if so
    * remove sections that are not numbered. In practice this only makes a small difference
    * since we rarely produce FPs for papers with numbered sections anyway.
    */
  private val allowNonPrefiex = "(?i)^(references|acknowledge?ments?|bibliography)$".r
  private def cleanPrefixedSections(titles: Seq[Seq[SectionTitle]]): Seq[Seq[SectionTitle]] = {
    val numSections = titles.map(_.size).sum
    if (numSections > 3) {
      val numPrefixedSections = titles.flatten.count(
        title =>
          isPrefixed(title.lines.head) ||
            allowNonPrefiex.pattern.matcher(title.toParagraph.text).matches()
      )
      val percentPrefixed = numPrefixedSections / numSections.toDouble
      if (percentPrefixed > PruneNonPrefixedSections) {
        logger.debug(
          "Number section titles detected, " +
            "pruning sections titles that were not numbered"
        )
        titles.map(
          pageTitles =>
            pageTitles.filter(
              title =>
                isPrefixed(title.lines.head) ||
                  allowNonPrefiex.findFirstMatchIn(title.toParagraph.text).isDefined
            )
        )
      } else {
        titles
      }
    } else {
      titles
    }
  }

  /** Find and separate out section headers from `pages`
    *
    * @param pages pages to give, each page being a sequence of sorted Paragraphs
    * @param layout Document layout
    * @return (nonSectionHeaders, sectionHeaders) for each page
    */
  private def stripSectionTitlesFromSortedParagraphs(
    pages: Seq[Seq[Paragraph]],
    layout: DocumentLayout
  ): Seq[(Seq[Paragraph], Seq[Paragraph])] = {

    val sectionTitles = pages.map { sortedParagraphs =>
      val lines = sortedParagraphs.iterator.flatMap(_.lines)

      var sectionTitles = List[SectionTitle]()
      var prevLine: Option[Line] = None
      var onTitle: Option[SectionTitle] = None

      lines.sliding(2).filter(_.size == 2).foreach { lines =>
        val curLine = lines.head
        val curLineBB = curLine.boundary
        val followingLine = lines.last

        if (isTitleStyle(curLine, layout) &&
            isBeneath(curLineBB, followingLine.boundary) &&
            !isList(curLine) && !isEquation(curLine)) {
          val sectionTitle = SectionTitle.build(curLine)
          val addToTitle = onTitle.isDefined && onTitle.get.isMatch(sectionTitle)
          if (addToTitle) {
            // The line continues the previous section title
            onTitle = Some(onTitle.get.addLine(curLine))
          } else {
            val prevLineWasTitle = onTitle.isDefined
            if (onTitle.isDefined) {
              // The previous line ended a section title
              if (isCompleteTitle(onTitle.get)) {
                sectionTitles = (onTitle ++ sectionTitles).toList
              }
              onTitle = None
            }
            if (isTitleStartText(curLine) &&
                (prevLineWasTitle || isFarFromPreviousLine(curLine, prevLine, layout)) &&
                isAlignedOrCentered(curLineBB, layout)) {
              onTitle = Some(sectionTitle)
            }
          }
        } else if (onTitle.isDefined) {
          // Previous line ended a section title
          if (isLineBeginningSection(curLine, onTitle.get, layout) &&
              isCompleteTitle(onTitle.get)) {
            sectionTitles = (onTitle ++ sectionTitles).toList
          }
          onTitle = None
        }
        if (isBeneath(curLineBB, followingLine.boundary)) {
          prevLine = Some(curLine)
        } else {
          prevLine = None
        }
      }

      if (onTitle.isDefined) {
        if (isCompleteTitle(onTitle.get)) {
          sectionTitles = (onTitle ++ sectionTitles).toList
        }
      }
      sectionTitles
    }

    val filteredSectionTitles = cleanPrefixedSections(sectionTitles)

    pages.zip(filteredSectionTitles).map {
      case (paragraphs, pageSectionTitles) =>
        val sectionTitleParagraphs = pageSectionTitles.map(_.toParagraph)
        val strippedText = Paragraph.removeSpans(
          sectionTitleParagraphs.map(_.span).sorted,
          paragraphs.sorted
        )
        (strippedText, sectionTitleParagraphs)
    }
  }
}
