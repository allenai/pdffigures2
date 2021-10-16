package org.allenai.pdffigures2

import org.apache.pdfbox.text.TextPosition

import java.text.Normalizer

/** Span of text denoted by the starting and ending line number, inclusive */
case class TextSpan(start: Int, end: Int) extends Ordered[TextSpan] {
  require(start >= 0, "line numbers cannot be < 0")
  require(start <= end)
  override def compare(that: TextSpan): Int = start.compare(that.start)
}

object Paragraph {
  val unprintableRegex = """[\p{Cc}\p{Cf}\p{Co}\p{Cn}]""".r

  def apply(lines: List[Line]): Paragraph = Paragraph(lines, Box.container(lines.map(_.boundary)))

  /** Copied from private method in PDBox's PDFTextStripper, normalizes unicode characters */
  def normalizeWord(word: String): String = {
    var builder: StringBuilder = null
    var p = 0
    var q = 0
    val strLength = word.length
    while (q < strLength) {
      val c = word.charAt(q)
      if (0xFB00 <= c && c <= 0xFDFF || 0xFE70 <= c && c <= 0xFEFF) {
        if (builder == null) {
          builder = new StringBuilder(strLength * 2)
        }
        builder.append(word.substring(p, q))
        if (c == 0xFDF2 && q > 0 && (word.charAt(q - 1) == 0x0627 ||
            word.charAt(q - 1) == 0xFE8D)) {
          builder.append("\u0644\u0644\u0647")
        } else {
          builder.append(Normalizer.normalize(word.substring(q, q + 1), Normalizer.Form.NFKC).trim)
        }
        p = q + 1
      }
      q += 1; q - 1
    }
    if (builder == null) {
      unprintableRegex.replaceAllIn(word, "")
    } else {
      builder.append(word.substring(p, q))
      builder.toString()
    }
  }

  /** Converts a Paragraph to a string, attempts to normalize and de-hyphenate the text */
  def convertToNormalizedString(paragraph: Paragraph): String = {
    val normalizedLines = paragraph.lines.map(_.words.map(w => normalizeWord(w.text)))
    val linesWithoutHyphens = normalizedLines.dropRight(1).map { line =>
      val lastWord = line.last
      if (lastWord.nonEmpty && lastWord.last == '-' &&
          lastWord.length > 1 && lastWord.exists(Character.isLetter)) {
        line.mkString(" ").dropRight(1) // Don't add following space and drop the hyphen
      } else {
        line.mkString(" ") + " "
      }
    }
    linesWithoutHyphens.mkString("") + normalizedLines.last.mkString(" ")
  }

  /** Converts paragraphs to a string, ignores paragraph delimitation */
  def convertToFlatNormalizedString(paragraphs: Iterable[Paragraph]): String = {
    if (paragraphs.nonEmpty) {
      convertToNormalizedString(Paragraph(paragraphs.flatMap(_.lines).toList))
    } else {
      ""
    }
  }

  /** Removes lines with line numbers contained inside `segments` from `paragraphs`.
    *
    * Paragraphs for which a subset of lines are removed will be split into two paragraphs
    * if line where removed from the middle, otherwise truncated into a shortened paragraph.
    *
    * @param segments spans of text to remove
    * @param paragraphs Paragraphs to remove prune, sorted
    * @return `paragraphs` with `segments` removed
    */
  def removeSpans(
    segments: Seq[TextSpan],
    paragraphs: Seq[Paragraph]
  ): Seq[Paragraph] = {
    if (segments.isEmpty) {
      return paragraphs
    }
    var strippedParagraphs = List[Paragraph]()
    val paragraphIterator = paragraphs.iterator

    var curParagraph = paragraphIterator.next()
    var curLines = curParagraph.lines // Lines from curParagraph we have yet to iterate past

    segments.sorted.foreach {
      case TextSpan(start, end) =>
        // Advance to the starting paragraph
        while (curLines.isEmpty || curParagraph.lines.last.lineNumber < start) {
          if (curLines.nonEmpty) {
            if (curLines.head.lineNumber == curParagraph.startLineNumber) {
              strippedParagraphs = curParagraph :: strippedParagraphs
            } else {
              val trailingParagraph = Paragraph(curLines)
              strippedParagraphs = trailingParagraph :: strippedParagraphs
            }
          }
          curParagraph = paragraphIterator.next()
          curLines = curParagraph.lines
        }

        // Make the lines before `start` a new paragraph
        val (linesBefore, linesAfter) = curLines.span(_.lineNumber < start)
        if (linesBefore.nonEmpty) strippedParagraphs = Paragraph(linesBefore) :: strippedParagraphs
        curLines = linesAfter

        // Skip to the last paragraph
        while (curParagraph.lines.last.lineNumber < end) {
          curParagraph = paragraphIterator.next()
          curLines = curParagraph.lines
        }

        curLines = curLines.dropWhile(_.lineNumber <= end)
    }

    if (curLines.nonEmpty) {
      if (curLines.head.lineNumber == curParagraph.startLineNumber) {
        strippedParagraphs = curParagraph :: strippedParagraphs
      } else {
        val trailingParagraph = Paragraph(curLines)
        strippedParagraphs = trailingParagraph :: strippedParagraphs
      }
    }

    strippedParagraphs.reverse ++ paragraphIterator
  }
}
case class Paragraph(lines: List[Line], boundary: Box) extends Ordered[Paragraph] {
  require(lines.nonEmpty, "paragraphs must be non-empty")
  require(
    lines.size == 1 || lines.sliding(2).forall(x => x.head.lineNumber < x.last.lineNumber),
    "paragraphs must contain lines in order"
  )
  def text: String = lines.map(_.text).mkString(" ")
  def startLineNumber: Int = lines.head.lineNumber
  def span: TextSpan = TextSpan(lines.head.lineNumber, lines.last.lineNumber)
  override def compare(that: Paragraph): Int = startLineNumber.compare(that.startLineNumber)
}

object Line {
  def apply(words: List[Word], lineNumber: Int): Line = {
    Line(words, Box.container(words.map(_.boundary)), lineNumber)
  }
}
case class Line(words: List[Word], boundary: Box, lineNumber: Int) {
  require(words.nonEmpty, "lines must be non-empty")
  def text: String = words.map(_.text).mkString(" ")
  override def toString: String = text
  def isHorizontal: Boolean = words.flatMap(_.positions).forall(_.getDir == 0)
}

case class Word(text: String, boundary: Box, positions: List[TextPosition]) {
  require(positions.nonEmpty, "words must be non-empty")
}
