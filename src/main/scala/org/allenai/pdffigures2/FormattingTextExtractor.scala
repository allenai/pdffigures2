package org.allenai.pdffigures2

object FormattingTextExtractor extends Logging {

  // Matches "Abstract. This Paper...", "Abstract", "Abstract-In this paper"
  // The second '-' in the regex is unicode character with a hyphen-like appearance
  private val AbstractRegex = """^(Abstract|ABSTRACT)(((—|-)[a-zA-Z]*)|.)?$""".r

  /** @return any Paragraphs that appears to part of an abstract in `page` */
  def selectAbstract(page: Page): Seq[Paragraph] = {
    val paragraphs = page.paragraphs
    val abstractStart =
      paragraphs.filter(p => AbstractRegex.pattern.matcher(p.lines.head.words.head.text).matches())
    if (abstractStart.size == 1) {
      logger.debug("Found abstract, marking as body text")
      val abstractParagraph = abstractStart.head

      // If the abstract was just a title (e.i. just the word 'Abstract') try to find the abstract
      // body beneath it and add that to bodyText as well
      val justTitle = abstractParagraph.lines.size == 1 &&
        abstractParagraph.lines.head.words.size == 1
      if (justTitle) {
        val titleCenter = abstractParagraph.boundary.xCenter
        val abstractBody = page.paragraphs.filter { p =>
          val yDistFromAbstract = p.boundary.y1 - abstractParagraph.boundary.y2
          val centered = Math.abs(p.boundary.xCenter - titleCenter) < 1
          val below = yDistFromAbstract < 20 && yDistFromAbstract > 3
          p.startLineNumber != abstractParagraph.startLineNumber && centered && below
        }
        if (abstractBody.size == 1) {
          logger.debug("Found text body beneath the abstract, marking as body text")
          Seq(abstractParagraph) ++ abstractBody
        } else {
          Seq(abstractParagraph)
        }
      } else {
        Seq(abstractParagraph)
      }
    } else {
      Seq()
    }
  }

  private def selectHeaderCandidates(
    textPages: Seq[Page],
    candidates: Seq[Option[Paragraph]],
    minConsistentHeaders: Int
  ): Seq[Option[Paragraph]] = {

    val nonEmptyCandidates = candidates.flatten
    if (nonEmptyCandidates.size >= minConsistentHeaders) {
      // Check for identical text
      val groupedByText = nonEmptyCandidates.map(x => x.text).groupBy(x => x)
      val (mostCommonText, count) = groupedByText.mapValues(_.size).maxBy(_._2)
      if (count >= minConsistentHeaders) {
        candidates.map {
          case np: Some[Paragraph] if np.get.text == mostCommonText => np
          case _ => None
        }
      } else {
        // Some headers have different text, they might include page numbers or alternate between
        // showing authors name and conference titles, ect. So we fall back on looking for lines
        // that share the same height. In theory we could do some check to make sure there is some
        // consistency between the text, but in practice this does not seem necessary
        case class Interval(x1: Double, x2: Double) {
          def intersects(other: Interval, tol: Double): Boolean =
            Math.abs(other.x1 - x1) < tol && Math.abs(other.x2 - x2) < tol
        }

        val heights = nonEmptyCandidates.map(p => Interval(p.boundary.y1, p.boundary.y2))
        val commonHeight = heights.find { interval =>
          heights.count(h => h.intersects(interval, 1.0f)) >= minConsistentHeaders
        }
        if (commonHeight.isDefined) {
          candidates.map {
            case p: Some[Paragraph] =>
              val boundary = p.get.boundary
              val interval = Interval(boundary.y1, boundary.y2)
              if (interval.intersects(commonHeight.get, 1)) p else None
            case _ => None
          }
        } else {
          Seq.fill(textPages.size)(None)
        }
      }
    } else {
      Seq.fill(textPages.size)(None)
    }
  }

  /** Find headers lines in a sequence of text pages
    *
    * This method finds the top paragraphs of each page, and if enough of those paragraphs have
    * the same text or same location returns them as header paragraphs.
    *
    * @param textPages to look for headers in
    * @param minConsistentHeaders minim number of headers to return, if we can't find consistent
    *                             headers for at least `minConsistentHeaders` page empty sequences
    *                             are returned
    * @return the headers per each page in `textPages`
    */
  def findHeaders(
    textPages: Seq[Page],
    minConsistentHeaders: Int
  ): Seq[Seq[Paragraph]] = {
    // Get two 'candidate' headers paragraphs for each page
    val (firstCandidates, secondCandidates) = textPages.map { textPage =>
      val topParagraphs = textPage.paragraphs.filter(paragraph => paragraph.boundary.y1 < 72 * 3)
      val top2Paragraphs = topParagraphs.sortBy(_.boundary.y1).take(2)
      if (top2Paragraphs.nonEmpty) {
        val firstCandidate = {
          val candidate = top2Paragraphs.head
          val aboveOtherText = topParagraphs.forall { paragraph =>
            paragraph.startLineNumber == candidate.startLineNumber ||
            Math.abs(candidate.boundary.y2 - paragraph.boundary.y2) > 3
          }
          val validCandidate = candidate.lines.size <= 3 && aboveOtherText
          if (validCandidate) Some(candidate) else None
        }
        val secondCandidate =
          if (top2Paragraphs.size > 1 && firstCandidate.isDefined) {
            val candidate = top2Paragraphs(1)
            val aboveOtherText = topParagraphs.forall { paragraph =>
              paragraph.startLineNumber == candidate.startLineNumber ||
              paragraph.startLineNumber == firstCandidate.get.startLineNumber ||
              Math.abs(candidate.boundary.y2 - paragraph.boundary.y2) > 3
            }
            val validCandidate = candidate.lines.size <= 3 && aboveOtherText
            if (validCandidate) Some(candidate) else None
          } else {
            None
          }
        (firstCandidate, secondCandidate)
      } else {
        (None, None)
      }
    }.unzip

    // Select which of those headers to use
    val firstHeaders = selectHeaderCandidates(textPages, firstCandidates, minConsistentHeaders)
    val prunedSecondCandidates = secondCandidates.zip(firstHeaders).map {
      case (sc: Some[Paragraph], Some(_)) => sc
      case _ => None
    }
    val secondHeaders =
      selectHeaderCandidates(textPages, prunedSecondCandidates, minConsistentHeaders)
    firstHeaders.zip(secondHeaders).map(x => (x._1 ++ x._2).toSeq)
  }

  private val PageNumberRegex = "[1-9][0-9]*".r // TODO maybe should include roman numeral or "page"

  /** For each page in `textPages`, return a line containing a page number if one was found
    *
    * @param textPages pages to search
    * @param minConsistentPageNumbers minimum number of page numbers to return, if fewer than this
    *                               number of page numbers are found only None is returned
    * @return Lines with pages numbers in each `textPage`
    */
  def findPageNumber(
    textPages: Seq[Page],
    minConsistentPageNumbers: Int
  ): Seq[Option[Line]] = {
    val pageNumberCandidates = textPages.map { textPage =>
      if (textPage.paragraphs.nonEmpty) {
        val lastParagraph = textPage.paragraphs.maxBy(_.boundary.y2)
        val lastLine = lastParagraph.lines.last
        if (PageNumberRegex.pattern.matcher(lastLine.text).matches()) {
          Some(lastLine)
        } else {
          None
        }
      } else {
        None
      }
    }

    val hasPageNumbers = minConsistentPageNumbers <= pageNumberCandidates.count(_.isDefined)
    if (hasPageNumbers) {
      logger.debug("Page numbers detected")
      pageNumberCandidates
    } else {
      Seq.fill(textPages.size)(None)
    }
  }

  /** Given all the text of a document, tries to identify bits of text that are not part of the
    * 'main content' of the document, that is text like page numbers, page headers, abstract, ect.
    * that don't follow the standard format of the rest of the body text.
    *
    * These bits of text are often atypical of the rest of the body text and can be hard to
    * classify using our standard heuristics, so its useful to strip them out as a pre-processing
    * step.
    *
    * @param textPages pages to remove text from
    * @return text with the extracted text filtered out
    */
  def extractFormattingText(textPages: List[Page]): List[PageWithClassifiedText] = {

    // To avoid FPs we only use the page numbers/headers we find if at least `minConsistent`
    // pages were found to have a header/page number
    val minConsistentPages =
      textPages.size - (if (textPages.size < 3) {
                          0
                        } else if (textPages.size < 5) {
                          1
                        } else {
                          2
                        })

    // Find headers and page numbers
    val headers = findHeaders(textPages, minConsistentPages)
    val pageNumbers = findPageNumber(textPages, minConsistentPages)

    // Look for an abstract in the first two pages
    val documentAbstract = textPages
      .take(2)
      .view
      .map { page =>
        (page.pageNumber, selectAbstract(page))
      }
      .find(_._2.nonEmpty)
    val abstractPageNum = if (documentAbstract.isDefined) Some(documentAbstract.get._1) else None

    val textWithoutHeaders = (textPages, headers, pageNumbers).zipped.map {
      case (textPage, header, pageNumber) =>
        if (abstractPageNum.isDefined && abstractPageNum.get > textPage.pageNumber) {
          logger.debug(s"Marking page ${textPage.pageNumber} as a cover page")
          PageWithClassifiedText(
            textPage.pageNumber,
            List(),
            ClassifiedText(formattingText = textPage.paragraphs)
          )
        } else {
          val pageNumberParagraph = if (pageNumber.isDefined) {
            Some(Paragraph(List(pageNumber.get)))
          } else {
            None
          }
          val pageNumberLocations = pageNumber.map(l => TextSpan(l.lineNumber, l.lineNumber))
          val headerLocations = header.map(_.span)
          val abstractText =
            if (abstractPageNum.nonEmpty && abstractPageNum.get ==
                  textPage.pageNumber) {
              documentAbstract.get._2
            } else {
              Seq()
            }
          // An easy way to get rid of titles, emails, and author names which can otherwise be hard
          // to classify, assume anything above the abstract is formatting text
          val aboveAbstractParagraph = if (abstractText.nonEmpty) {
            val abstractY1 = abstractText.map(_.boundary.y1).min
            val alreadyRemoved = header.map(_.startLineNumber) ++
              abstractText.map(_.startLineNumber)
            textPage.paragraphs.filter { paragraph =>
              paragraph.boundary.y2 < abstractY1 &&
              !alreadyRemoved.contains(paragraph.startLineNumber)
            }
          } else {
            Seq()
          }
          val aboveAbstractLocations = aboveAbstractParagraph.map(_.span)
          val abstractLocations = abstractText.map(_.span)
          val toRemove =
            headerLocations ++ pageNumberLocations ++ abstractLocations ++ aboveAbstractLocations
          val strippedText = Paragraph.removeSpans(toRemove, textPage.paragraphs)
          PageWithClassifiedText(
            textPage.pageNumber,
            strippedText,
            ClassifiedText(
              header,
              pageNumberParagraph.toSeq ++ aboveAbstractParagraph,
              abstractText
            )
          )
        }
    }
    textWithoutHeaders
  }
}
