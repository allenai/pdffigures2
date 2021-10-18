package org.allenai.pdffigures2

import org.apache.pdfbox.pdmodel.font.PDFont

import scala.collection.{ immutable, mutable }

/** Store some statistics about the document as a whole */
case class DocumentLayout(
  twoColumns: Boolean,
  fontCounts: immutable.Map[PDFont, Double],
  standardFontSize: Option[Double],
  averageFontSize: Double,
  averageWordSpacing: Double,
  trustLeftMargin: Boolean,
  leftMargins: immutable.Map[Int, Double],
  medianLineSpacing: Double,
  standardWidthBucketed: Option[Double]
) {
  require(averageFontSize >= 0, "font size cannot be < 0")
  require(averageWordSpacing > 0, "word spacing should only consider words with > 0 spacing")
  require(medianLineSpacing > 0, "line spacing should only consider lines with > 0 spacing")
  require(standardFontSize.isEmpty || standardFontSize.get >= 0, "Font size cannot be < 0")
  require(
    standardWidthBucketed.isEmpty || standardWidthBucketed.get > 0,
    "line widths should be > 0"
  )
  require(leftMargins.nonEmpty, "Should have lefts margins for at least some text")
  require(fontCounts.nonEmpty, "Should have fonts for at least some text")
  require(leftMargins.valuesIterator.forall(v => v >= 0 && v <= 1), "Should contain percentages")
  require(fontCounts.valuesIterator.forall(v => v >= 0 && v <= 1), "Should contain percentages")
}

object DocumentLayout extends Logging {

  val LineWidthBucketSize = 2

  private val TwoColumnMaxUsageDifference = 0.40
  private val TwoColumnMaxXDifference = 0.40

  private val MinCommonLineWidthUse = 0.4

  private val TrustMarginsTwoColumnThreshold = 0.65
  private val TrustMarginsNumMarginsToCount = 3
  private val TrustMarginsOneColumnThreshold = 0.55

  /** Calculates the weighted median, that is given a list of (value, weight) pairs returns the
    * value such that the total weight of the values that are large and the total weight of the
    * values that are smaller are as close as possible
    */
  def weightedMedian(inputs: immutable.Vector[(Double, Int)]): Double = {
    require(inputs.nonEmpty)
    var sorted = inputs.sortBy(_._1)
    var weightedRemovedFromStart = 0
    var weightedRemovedFromEnd = 0
    while (sorted.size != 1) {
      if (weightedRemovedFromEnd + sorted.last._2 < weightedRemovedFromStart + sorted.head._2) {
        weightedRemovedFromEnd += sorted.last._2
        sorted = sorted.dropRight(1)
      } else {
        weightedRemovedFromStart += sorted.head._2
        sorted = sorted.drop(1)
      }
    }
    sorted.head._1
  }

  def apply(textPages: immutable.Seq[Page]): Option[DocumentLayout] = {
    var totalWordSpacing = 0.0
    var totalWordSpaces = 0
    val leftMargins = mutable.Map[Int, Int]().withDefault(_ => 0)
    val fontCounts = mutable.Map[PDFont, Int]().withDefault(_ => 0)
    val lineWidths = mutable.Map[Double, Int]().withDefault(_ => 0)
    val fontSizeCounts = mutable.Map[Double, Int]().withDefault(_ => 0)
    val lineSpacing = mutable.ListBuffer[(Double, Int)]()

    textPages.foreach { textPage =>
      var prevLineBB: Option[Box] = None
      textPage.paragraphs.foreach { paragraph =>
        paragraph.lines.filter(_.isHorizontal).foreach { line =>
          val weight = line.words.size
          val x1 = Math.round(line.boundary.x1).toInt
          leftMargins.update(x1, leftMargins(x1) + weight)
          if (prevLineBB.isDefined) {
            val bb = line.boundary
            val space = line.boundary.y1 - prevLineBB.get.y2
            if (prevLineBB.get.x1 < bb.x2 && prevLineBB.get.x2 > bb.x1 && space > 0) {
              lineSpacing.append((space, weight))
            }
          }
          val w = line.boundary.width
          val lowerBucket = Math.floor(w / LineWidthBucketSize) * LineWidthBucketSize
          val upperBucket = Math.ceil(w / LineWidthBucketSize) * LineWidthBucketSize
          lineWidths.update(lowerBucket, lineWidths(lowerBucket) + weight)
          lineWidths.update(upperBucket, lineWidths(upperBucket) + weight)

          prevLineBB = Some(line.boundary)
          var prevWord: Option[Word] = None
          line.words.foreach { word =>
            if (prevWord.isDefined) {
              val prevWordBB = prevWord.get.boundary
              val spacing = word.boundary.x1 - prevWordBB.x2
              if (spacing > 0) {
                totalWordSpacing += spacing
                totalWordSpaces += 1
              }
            }
            word.positions.foreach { pos =>
              fontCounts.update(pos.getFont, fontCounts(pos.getFont) + 1)
              val fontSize = pos.getFontSizeInPt
              fontSizeCounts.update(fontSize, fontSizeCounts(fontSize) + 1)
            }
            prevWord = Some(word)
          }
        }
      }
    }
    val totalChars = fontCounts.values.sum
    val totalLines = lineWidths.values.sum / 2

    if (totalWordSpaces == 0 || totalChars == 0 || lineSpacing.isEmpty || totalLines == 0) {
      // Not enough information to build a valid layout, usually a document where we were unable
      // to extract much (or any) text
      None
    } else {
      val (mostCommonFontSize, mostCommonFontSizeCount) = fontSizeCounts.maxBy(_._2)
      val standardFontSize = if (mostCommonFontSizeCount > totalChars / 2.0) {
        Some(mostCommonFontSize)
      } else {
        None
      }
      val averageFontSize =
        fontSizeCounts.map { case (k, v) => k * v }.sum / fontSizeCounts.values.sum

      val medianLineSpacing = weightedMedian(lineSpacing.toVector)

      val (mostCommonWidth, mostCommonWidthCount) = lineWidths.maxBy(_._2)
      val standardLineWidth = if (mostCommonWidthCount > totalLines * MinCommonLineWidthUse) {
        Some(mostCommonWidth)
      } else {
        None
      }

      val sortedLeftMargins = leftMargins.toSeq.sortBy(-_._2)
      val top2Margins = sortedLeftMargins.take(2)
      val mostCommon = top2Margins.head
      val secondMostCommonOpt = top2Margins.tail.headOption
      val twoColumn = if (secondMostCommonOpt.isDefined) {
        val secondMostCommon = secondMostCommonOpt.get
        val diff = Math.abs(mostCommon._2 - secondMostCommon._2) /
          (mostCommon._2 + secondMostCommon._2).toDouble
        diff < TwoColumnMaxUsageDifference &&
        Math.abs(mostCommon._1 - secondMostCommon._1) > TwoColumnMaxXDifference
      } else {
        false
      }
      if (twoColumn) {
        logger.debug("Document was two column")
      } else {
        logger.debug("Document was not two column")
      }

      val fontPercents = fontCounts.map { case (k, v) => (k, v / totalChars.toDouble) }.toMap

      val totalMarginCounts = leftMargins.values.sum
      val sortedMarginsByPercent = sortedLeftMargins.map {
        case (margin, count) => (margin, count / totalMarginCounts.toDouble)
      }
      val trustMargins = if (twoColumn) {
        val wordsInTopMargins =
          sortedMarginsByPercent.take(TrustMarginsNumMarginsToCount * 2).map(_._2).sum
        wordsInTopMargins > TrustMarginsTwoColumnThreshold
      } else {
        val wordsInTopMargins =
          sortedMarginsByPercent.take(TrustMarginsNumMarginsToCount).map(_._2).sum
        wordsInTopMargins > TrustMarginsOneColumnThreshold
      }

      if (!trustMargins) logger.debug("Margins appear to be inconsistent, won't use in heuristics")

      val layout = DocumentLayout(
        twoColumn,
        fontPercents,
        standardFontSize,
        averageFontSize,
        totalWordSpacing / totalWordSpaces,
        trustMargins,
        sortedMarginsByPercent.toMap,
        medianLineSpacing,
        standardLineWidth
      )
      Some(layout)
    }
  }
}
