package org.allenai.pdffigures2

import org.apache.pdfbox.pdmodel.font.PDFont
import FigureType._

case class CaptionStart(
  header: String,
  name: String,
  figType: FigureType,
  numberSyntax: String,
  line: Line,
  nextLine: Option[Line],
  page: Int,
  paragraphStart: Boolean,
  lineEnd: Boolean
) {
  val figId = (figType, name)
  val colonMatch = numberSyntax == ":"
  val periodMatch = numberSyntax == "."
  val allCapsFig = header.startsWith("FIG")
  val allCapsTable = header == "TABLE"
  val figAbbreviated = header == "Fig."
}

object CaptionDetector extends Logging {

  // When to 'give up' on a caption, if we detect `MaxDuplicateCaptionNames` for one caption prefix
  // (like 3 instances of the text 'Figure 3') or `MaxSamePageDuplicateCaptionNames` instances on
  // the same page, and are unable to prune the duplicates, then give up on those caption prefixes.
  private val MaxDuplicateCaptionNames = 3
  private val MaxSamePageDuplicateCaptionNames = 2

  // Very rare PDFs have some crazy text issues causing paragraphs to be stuffed into
  // single lines (even Preview can't parse these PDFs), so we do a sanity check
  // make sure we don't use such a line in caption by checking that the line's height is reasonable
  private val MaxHeightForCaptionLines = 60
  private val MinCommonFontPercentage = 0.4

  /** Abstract class for 'Filters' that either 'accept' or 'reject' `CaptionStart` objects
    * depending on the formatting for that CaptionStart. A filter should accept captions
    * that share some specific formatting characteristic
    */
  abstract class CandidateFilter {
    val name: String
    def accept(cc: CaptionStart): Boolean
  }

  private case class ColonOnly() extends CandidateFilter {
    val name = "Colon Only"
    def accept(cc: CaptionStart): Boolean = cc.colonMatch
  }

  private case class NonStandardFont(
    standardFont: PDFont,
    types: Set[FigureType]
  ) extends CandidateFilter {
    val name = s"Non Standard Font: ${types.toList}"
    def accept(cc: CaptionStart): Boolean =
      !types.contains(cc.figType) ||
        cc.line.words.head.positions.head.getFont != standardFont
  }

  private case class PeriodOnly() extends CandidateFilter {
    val name = "Period Only"
    def accept(cc: CaptionStart): Boolean = cc.periodMatch
  }

  private case class AllCapsFigOnly() extends CandidateFilter {
    val name = "All Caps Figures Only"
    def accept(cc: CaptionStart): Boolean = cc.allCapsFig || cc.figType == FigureType.Table
  }

  private case class AllCapsTableOnly() extends CandidateFilter {
    val name = "All Caps Table Only"
    def accept(cc: CaptionStart): Boolean = cc.allCapsTable || cc.figType == FigureType.Figure
  }

  private case class AbbreviatedFigOnly() extends CandidateFilter {
    val name = "Abbreviated Fig Only"
    def accept(cc: CaptionStart): Boolean = cc.figAbbreviated || cc.figType == FigureType.Table
  }

  private case class LineEndOnly() extends CandidateFilter {
    val name = "Line End Only"
    def accept(cc: CaptionStart): Boolean = cc.lineEnd
  }

  private case class FigureHasFollowingTextOnly() extends CandidateFilter {
    val name = "Figure Following Text"
    def accept(cc: CaptionStart): Boolean = cc.figType == FigureType.Table || !cc.lineEnd
  }

  private case class LeftAlignedOnly(figureOnly: Boolean) extends CandidateFilter {
    val name = "Left Aligned" + (if (figureOnly) " Figures" else "")
    def accept(cc: CaptionStart): Boolean = {
      figureOnly && cc.figType == FigureType.Table || (if (cc.nextLine.isDefined) {
                                                         Math.abs(
                                                           cc.line.boundary.x1 - cc.nextLine.get.boundary.x1
                                                         ) < 1
                                                       } else {
                                                         true
                                                       })
    }
  }

  // Words that might start captions
  private val captionStartRegex = """^(Figure.|Figure|FIGURE|Table|TABLE||Fig.|Fig|FIG.|FIG)$""".r
  // Finds caption number that might follow the given word, occasionally this number will be
  // incorrectly chunked with the following word if ending with : or '.' so we allow following text
  private val captionNumberRegex =
    """^([1-9][0-9]*.[1-9][0-9]*|[1-9][0-9]*|[IVX]+|[1-9I][0-9I]*|[A-D].[1-9][0-9]*)($|:|.)?""".r

  /** Identify where the captions are inside a sequence of pages.
    *
    * We find all lines that might be the start of a caption by using regex to look for lines
    * starting with terms like 'Figure 1' or 'Table 1'. This will often produce false positives,
    * to remove the FPs we filter out lines that are formatted in an inconsistent manner with the
    * other lines. Note we can tolerate a few false positive here since upstream states will
    * (hopefully) realize the FP is not near any plausible figure and skip it.
    *
    * @param pages to find the captions in
    * @param layout document layout information
    * @return caption starts within the text
    */
  def findCaptions(pages: Seq[Page], layout: DocumentLayout): Seq[CaptionStart] = {
    val candidates = findCaptionCandidates(pages)

    val (standardFont, count) = layout.fontCounts.maxBy(_._2)
    val fontFilters = if (count > MinCommonFontPercentage) {
      Seq(
        NonStandardFont(standardFont, Set(FigureType.Figure, FigureType.Table)),
        NonStandardFont(standardFont, Set(FigureType.Table)),
        NonStandardFont(standardFont, Set(FigureType.Figure))
      )
    } else {
      Seq()
    }
    val filters = Seq(ColonOnly(), AllCapsFigOnly(), AllCapsTableOnly()) ++
      fontFilters ++ Seq(
      AbbreviatedFigOnly(),
      FigureHasFollowingTextOnly(),
      PeriodOnly(),
      LeftAlignedOnly(false),
      LeftAlignedOnly(true),
      LineEndOnly()
    )
    selectCaptionCandidates(candidates, filters)
  }

  def findCaptionCandidates(pages: Seq[Page]): Seq[CaptionStart] = {
    val candidates = pages.flatMap { page =>
      page.paragraphs.flatMap { paragraph =>
        var paragraphStart = true
        paragraph.lines.view.zipWithIndex.flatMap {
          case (line, lineNum) =>
            val firstWord = line.words.head.text
            // PDFBox might add a space between the 'Fig' and '.', due to imperfect spacing
            // deductions in rare cases, we handle this by grouping the two together
            val (headerStr, wordNumber) =
              if (line.words.size > 2 &&
                  line.words(1).text == ".") {
                (firstWord + ".", 2)
              } else {
                (firstWord, 1)
              }
            val captionStartMatchOpt = captionStartRegex.findFirstMatchIn(firstWord)
            val candidates = if (captionStartMatchOpt.nonEmpty && line.words.size > 1) {
              val captionStartMatch = captionStartMatchOpt.get
              val numberStr = line.words(wordNumber).text
              val captionEndMatchOp = captionNumberRegex.findFirstMatchIn(numberStr)
              val saneHeight = line.boundary.height < MaxHeightForCaptionLines
              if (!saneHeight) {
                logger.debug("Warning: Crazy height for caption line, skipping")
              }
              if (saneHeight && captionEndMatchOp.nonEmpty) {
                val captionEndMatch = captionEndMatchOp.get
                val name = captionEndMatch.group(1)
                val figType = if (captionStartMatch.group(1).charAt(0) == 'F') {
                  FigureType.Figure
                } else {
                  FigureType.Table
                }
                val nextLine = if (lineNum == paragraph.lines.size - 1) {
                  None
                } else {
                  Some(paragraph.lines(lineNum + 1))
                }
                val candidate = CaptionStart(
                  headerStr,
                  name,
                  figType,
                  captionEndMatch.group(2),
                  line,
                  nextLine,
                  page.pageNumber,
                  paragraphStart,
                  captionEndMatch.end == numberStr.length && line.words.size == wordNumber + 1
                )
                Some(candidate)
              } else {
                None
              }
            } else {
              None
            }
            paragraphStart = false
            candidates
        }
      }
    }
    candidates
  }

  def selectCaptionCandidates(
    candidates: Seq[CaptionStart],
    filters: Seq[CandidateFilter]
  ): Seq[CaptionStart] = {

    var groupedById = candidates.groupBy(_.figId)
    var removedAny = true
    // Keep filtering candidates until we have no duplicates for each Figure/Table
    // mentioned in the document, or can't find anything else to prune
    while (removedAny && groupedById.values.exists(_.size > 1)) {
      val filterToUse = filters.find { filter =>
        val filterRemovesAny = groupedById.exists {
          case (_, candidatesForId) => candidatesForId.exists(!filter.accept(_))
        }
        val filterRemovesGroup = groupedById.exists {
          case (_, candidatesForId) => candidatesForId.forall(!filter.accept(_))
        }
        filterRemovesAny && !filterRemovesGroup
      }
      if (filterToUse.nonEmpty) {
        groupedById = groupedById.map {
          case (figId, candidatesForId) => (figId, candidatesForId.filter(filterToUse.get.accept))
        }
        logger.debug(
          s"Applied filter ${filterToUse.get.name}, " +
            s"${groupedById.values.map(_.size).sum} remaining"
        )
      } else {
        // No filters applied, as a last resort try use PDFBox's paragraph deliminations to
        // disambiguate. This is slightly error-prone due to paragraph chunking errors but better
        // than nothing. If even that does not work give up
        removedAny = false
        groupedById = groupedById.map {
          case (figureId, candidatesForId) =>
            val filtered = candidatesForId.filter(_.paragraphStart)
            if (filtered.nonEmpty) {
              if (filtered.size < candidatesForId.size) removedAny = true
              (figureId, filtered)
            } else {
              (figureId, candidatesForId)
            }
        }
        if (!removedAny) {
          logger.debug(
            s"Filtered for paragraph starts, " +
              s"${groupedById.values.map(_.size).sum} remaining"
          )
        }
      }
    }

    // Caption groups with 4+ candidates or over 2 candidates on any page are dropped
    val filteredCaptionStarts = groupedById.filter {
      case (figId, captions) =>
        if (captions.size > MaxDuplicateCaptionNames ||
            captions.groupBy(_.page).map(_._2.size).max > MaxSamePageDuplicateCaptionNames) {
          logger.debug(s"Unable to disambiguate caption candidates for $figId, dropping")
          false
        } else {
          true
        }
    }
    filteredCaptionStarts.values.flatten.toSeq
  }
}
