package org.allenai.pdffigures2

import scala.annotation.tailrec

/** Code to break a PDF into section/paragraph pairs once the section titles
  * have been located
  */
object SectionedTextBuilder {

  object PdfText {
    def apply(paragraph: Paragraph, page: Int): PdfText = {
      val text = Paragraph.convertToNormalizedString(paragraph)
      PdfText(text, page, paragraph.boundary)
    }
  }

  /** Text inside a PDF and its location and page number */
  case class PdfText(text: String, page: Int, region: Box) {
    require(page >= 0, "Page cannot be less than 0")
    override def toString: String = text
  }

  object DocumentSection {
    def fromParagraphs(
      header: Option[Paragraph],
      text: List[Paragraph],
      pageNumber: Int
    ): DocumentSection = {
      DocumentSection(
        if (header.isDefined) Some(PdfText(header.get, pageNumber)) else None,
        text.map(p => PdfText(p, pageNumber))
      )
    }
  }

  /** Section of a scholarly document
    *
    * @param title section title, None if the text appeared in the document before any section
    *              title was found
    * @param paragraphs section text broken up into paragraphs
    */
  case class DocumentSection(title: Option[PdfText], paragraphs: Seq[PdfText]) {
    def titleText = title.map(_.text)
    def paragraphsText = paragraphs.map(_.text)
    def bodyText = paragraphsText.mkString("\n")
  }

  @tailrec
  private def mergeInSections(
    headers: List[Paragraph],
    paragraphs: List[Paragraph],
    pageNumber: Int,
    curSections: List[DocumentSection]
  ): List[DocumentSection] = {
    if (headers.isEmpty) {
      DocumentSection.fromParagraphs(None, paragraphs, pageNumber) :: curSections
    } else if (paragraphs.isEmpty) {
      headers.map(h => DocumentSection.fromParagraphs(Some(h), List(), pageNumber)) ::: curSections
    } else {
      val firstHeader = headers.head
      val firstHeaderLineNum = firstHeader.startLineNumber
      val firstParagraphLineNum = paragraphs.head.startLineNumber
      if (firstHeaderLineNum > firstParagraphLineNum) {
        val (beforeHeader, afterHeader) =
          paragraphs.span(p => p.lines.head.lineNumber < firstHeaderLineNum)
        mergeInSections(
          headers,
          afterHeader,
          pageNumber,
          DocumentSection.fromParagraphs(None, beforeHeader, pageNumber) :: curSections
        )
      } else if (headers.tail.isEmpty) {
        DocumentSection.fromParagraphs(headers.headOption, paragraphs, pageNumber) :: curSections
      } else {
        val secondHeaderLineNum = headers.tail.head.startLineNumber
        if (secondHeaderLineNum < firstParagraphLineNum) {
          mergeInSections(
            headers.tail,
            paragraphs,
            pageNumber,
            DocumentSection.fromParagraphs(headers.headOption, List(), pageNumber) :: curSections
          )
        } else {
          val (beforeHeader, afterHeader) =
            paragraphs.span(p => p.lines.head.lineNumber < secondHeaderLineNum)
          val sections = DocumentSection.fromParagraphs(
            Some(firstHeader),
            beforeHeader,
            pageNumber
          ) :: curSections
          mergeInSections(headers.tail, afterHeader, pageNumber, sections)
        }
      }
    }
  }

  /** Transforms the text and section titles contained in `pages` into a sequence of
    * DocumentSections
    *
    * Any paragraphs that start before any section title will be put into a section without any
    * title. Otherwise each section title will be become a DocumentSection whose text is built from
    * all the paragraphs in `pages` that come after the title but before the following title
    */
  def buildSectionedText(pages: List[ClassifiedPage]): Seq[DocumentSection] = {
    val sortedPages = pages.sortBy(_.pageNumber)
    require(
      sortedPages
        .sliding(2)
        .forall(pages => pages.size == 1 || pages.head.pageNumber == pages.last.pageNumber - 1),
      "Must have consecutive page numbers"
    )
    require(sortedPages.head.pageNumber == 0, "Must have a first page")
    val mergedPerPage = sortedPages.map(
      page =>
        mergeInSections(
          page.classifiedText.sectionTitles.toList.sorted,
          page.paragraphs.toList,
          page.pageNumber,
          List()
        ).reverse
    )
    // For sections that cross multiple pages, merge them into one section
    val mergedText = mergedPerPage.foldLeft(List[DocumentSection]()) {
      case (cur, nextPage) =>
        if (nextPage.head.title.isEmpty && cur.nonEmpty) {
          val sectionOnBothPages =
            cur.last.copy(paragraphs = cur.last.paragraphs ++ nextPage.head.paragraphs)
          (cur.dropRight(1) :+ sectionOnBothPages) ++ nextPage.tail
        } else {
          cur ++ nextPage
        }
    }
    mergedText
  }
}
