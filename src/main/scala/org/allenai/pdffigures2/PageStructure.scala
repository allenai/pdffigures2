package org.allenai.pdffigures2

/** Class to hold pieces of text that have been classified */
case class ClassifiedText(
  pageHeaders: Seq[Paragraph] = Seq(),
  formattingText: Seq[Paragraph] = Seq(),
  abstractText: Seq[Paragraph] = Seq(),
  sectionTitles: Seq[Paragraph] = Seq()
) {
  def allText: Seq[Paragraph] = pageHeaders ++ formattingText ++ sectionTitles ++ abstractText
}

abstract class Page {
  def pageNumber: Int
  def paragraphs: Seq[Paragraph]
  require(
    paragraphs.size <= 1 ||
      paragraphs.sliding(2).forall(p => p.head.span.end < p.last.span.start),
    "Pages must contain ordered, non-overlapping text"
  )
}

abstract class ClassifiedPage extends Page {
  def classifiedText: ClassifiedText
}

case class PageWithText(
  pageNumber: Int,
  paragraphs: Seq[Paragraph]
) extends Page

case class PageWithClassifiedText(
  pageNumber: Int,
  paragraphs: Seq[Paragraph],
  classifiedText: ClassifiedText
) extends ClassifiedPage

case class PageWithGraphics(
  pageNumber: Int,
  paragraphs: Seq[Paragraph],
  graphics: Seq[Box],
  nonFigureGraphics: Seq[Box],
  classifiedText: ClassifiedText
) extends ClassifiedPage

case class PageWithCaptions(
  pageNumber: Int,
  captions: Seq[CaptionParagraph],
  graphics: Seq[Box],
  nonFigureGraphics: Seq[Box],
  paragraphs: Seq[Paragraph],
  classifiedText: ClassifiedText
) extends ClassifiedPage {
  require(captions.forall(_.page == pageNumber), "captions should be on the same page")
}

case class PageWithBodyText(
  pageNumber: Int,
  classifiedText: ClassifiedText,
  captions: Seq[CaptionParagraph],
  graphics: Seq[Box],
  nonFigureGraphics: Seq[Box],
  bodyText: Seq[Paragraph],
  otherText: Seq[Paragraph]
) extends ClassifiedPage {
  require(captions.forall(_.page == pageNumber), "captions should be on the same page")
  // Note "classifiedText" is not returned by these methods, I have found it to be slightly more
  // effective to just ignore `classifiedText` then to treat it as `nonFigureText` since page
  // numbers and headers sometimes cause problems for our region generation methods
  override def paragraphs: Seq[Paragraph] = (bodyText ++ otherText).sorted
  def nonFigureText: Seq[Paragraph] = bodyText ++ captions.map(_.paragraph)
  def nonFigureContent: Seq[Box] = nonFigureText.map(_.boundary) ++ nonFigureGraphics
  def possibleFigureContent = graphics ++ otherText.map(_.boundary)
  def allContent = possibleFigureContent ++ nonFigureContent
}

case class PageWithFigures(
  pageNumber: Int,
  paragraphs: Seq[Paragraph],
  classifiedText: ClassifiedText,
  figures: Seq[Figure],
  failedCaptions: Seq[Caption]
) extends ClassifiedPage {
  require(figures.forall(_.page == pageNumber), "figures should be on the same page")
  require(failedCaptions.forall(_.page == pageNumber), "captions should be on the same page")
}
