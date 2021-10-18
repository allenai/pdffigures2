package org.allenai.pdffigures2

import org.apache.pdfbox.cos.COSBase
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.{ PDDocument, PDPage }
import org.apache.pdfbox.text.{ PDFTextStripper, TextPosition }

import java.io.Writer
import scala.collection.{ immutable, mutable }

object TextExtractor {

  /** For vertical text, ensure the minimum width is fixed to this value */
  private val MinVerticalTextWidth = 2

  /** Min height for which to clip the height of text */
  private val MinHeightToClipText = 40

  /** Height to clip overly tall text to */
  private val HeightToCLipTextTo = 10

  def extractText(document: PDDocument): immutable.List[PageWithText] = {
    val textExtractor = new TextExtractor()
    textExtractor.loadText(document)
    textExtractor.accumulatedPages
  }
}

/* Class the overrides PDFTextStripper to store text in a structured way rather then
 * writing to an output. Rather hacky since PDFTextStripper is very selective about what
 * it exposes to subclasses
 */
private class TextExtractor extends PDFTextStripper with Logging {
  // TODO if we override + copy/past writePage we could be less hacky about how we collect
  // elements and remove some of the processing we do not need

  // We need a dummy writer to pass to the superclass since some of PDFTextStripper text grouping
  // code sends writes directly to a Writer object.
  private class NullWriter extends Writer {
    override def flush(): Unit = {}
    override def write(cbuf: Array[Char], off: Int, len: Int): Unit = {}
    override def close(): Unit = {}
  }

  private val wordsInLine = mutable.ListBuffer[Word]()
  private val linesInParagraph = mutable.ListBuffer[Line]()
  private val paragraphsInPage = mutable.ListBuffer[Paragraph]()
  private val pages = mutable.ListBuffer[PageWithText]()
  private var onPage = 0
  private var onLine = 0

  def accumulatedPages: immutable.List[PageWithText] = pages.toList

  def loadText(doc: PDDocument): Unit = super.writeText(doc, new NullWriter())

  override def writeText(doc: PDDocument, outputStream: Writer): Unit = ??? // Use loadText

  override def processPage(page: PDPage): Unit = {
    super.processPage(page)
  }

  override def startDocument(document: PDDocument): Unit = {
    wordsInLine.clear()
    linesInParagraph.clear()
    paragraphsInPage.clear()
    pages.clear()
    onPage = 0
    onLine = 0
  }

  override def processOperator(operation: String, arguments: java.util.List[COSBase]): Unit = {
    if (Thread.interrupted()) throw new InterruptedException()
    super.processOperator(operation, arguments)
  }

  override def endPage(page: PDPage): Unit = {
    pages.append(PageWithText(onPage, paragraphsInPage.toList))
    paragraphsInPage.clear()
    onPage += 1
  }

  override def writeParagraphEnd(): Unit = {
    lineFinished()
    if (linesInParagraph.nonEmpty) {
      val newParagraph = Paragraph(linesInParagraph.toList)
      paragraphsInPage.append(newParagraph)
      linesInParagraph.clear()
    }
    super.writeParagraphEnd()
  }

  override protected def writeString(
    text: String,
    textPositions: java.util.List[TextPosition]
  ): Unit = {
    // The given text might include spaces if spaces were encoded as characters in the PDF, to
    // handle this we split the given text on space characters so we have individual words
    var onTextPosition = 0
    var minX, minY = Float.MaxValue
    var maxX, maxY = -1.0f
    val stringBuilder = new StringBuilder()
    val wordTextPositions = mutable.ListBuffer[TextPosition]()

    def addText(): Unit = {
      if (stringBuilder.nonEmpty) {
        if (minX == maxX && wordTextPositions.forall(p => p.getDir == 90 || p.getDir == 270)) {
          // Vertical text can sometimes be given a 0 width text box, so for these cases we give the
          // text a very conservative minimum width
          val bb = Box(minX - TextExtractor.MinVerticalTextWidth, minY, maxX, maxY)
          wordsInLine.append(Word(stringBuilder.toString(), bb, wordTextPositions.toList))
        } else if (minX <= maxX && minY <= maxY) {
          val bb = Box(minX, minY, maxX, maxY)
          wordsInLine.append(Word(stringBuilder.toString(), bb, wordTextPositions.toList))
        } else {
          logger.warn(
            s"""Word "${stringBuilder.toString()}" on page $onPage had a negative """ +
              "height or width, skipping"
          )
        }
      }
    }

    while (onTextPosition != textPositions.size) {
      val pos = textPositions.get(onTextPosition)
      val unicode = pos.getUnicode
      if (unicode.length == 1 &&
          (Character.isISOControl(unicode.charAt(0)) ||
          Character.isWhitespace(unicode.charAt(0)))) {
        if (stringBuilder.nonEmpty) {
          addText()
          wordTextPositions.clear()
          stringBuilder.clear()
          minX = Float.MaxValue
          maxX = -1.0f
          minY = Float.MaxValue
          maxY = -1.0f
        }
      } else {
        stringBuilder.append(unicode)
        // PDFBox can occasionally wildly overestimate the height of text, so if things look really
        // wrong we clip the text to a sensible amount
        val height = if (pos.getHeight > TextExtractor.MinHeightToClipText) {
          TextExtractor.HeightToCLipTextTo
        } else {
          pos.getHeight
        }
        minX = Math.min(pos.getX, minX)
        minY = Math.min(pos.getY - height, minY)
        maxX = Math.max(maxX, pos.getX + pos.getWidth)
        maxY = Math.max(maxY, pos.getY)
        wordTextPositions.append(pos)
      }
      onTextPosition += 1
    }
    addText()
  }

  def lineFinished(): Unit = {
    if (wordsInLine.nonEmpty) {
      val lineBox = Box.container(wordsInLine.map(_.boundary))
      linesInParagraph.append(Line(wordsInLine.toList, lineBox, onLine))
      wordsInLine.clear()
      onLine += 1
    }
  }

  override def writeLineSeparator(): Unit = lineFinished()

  // Overwrite this methods for efficiency since they do not do any text organization
  override def writePageStart(): Unit = {}
  override def writeWordSeparator(): Unit = {}
  override def writeParagraphStart(): Unit = {}
  override def writePageEnd(): Unit = {}
  override def startArticle(isLTR: Boolean): Unit = {}
  override def endArticle(): Unit = {}
  override def writeString(text: String): Unit = {}
}
