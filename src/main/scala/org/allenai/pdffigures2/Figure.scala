package org.allenai.pdffigures2

import java.awt.image.BufferedImage


object FigureType extends Enumeration {
  type FigureType = Value
  val Table, Figure = Value
}
import FigureType._

case class CaptionParagraph(name: String, figType: FigureType, page: Int, paragraph: Paragraph) {
  def boundary: Box = paragraph.boundary
  def startLineNumber: Int = paragraph.startLineNumber
  def text: String = Paragraph.convertToNormalizedString(paragraph)
}

object Caption {
  def apply(captionParagraph: CaptionParagraph): Caption = {
    Caption(
      captionParagraph.name,
      captionParagraph.figType,
      captionParagraph.page,
      captionParagraph.text,
      captionParagraph.boundary
    )
  }
}
case class Caption(name: String, figType: FigureType, page: Int, text: String, boundary: Box)

case class Figure(
  name: String,
  figType: FigureType,
  page: Int,
  caption: String,
  imageText: Seq[String],
  captionBoundary: Box,
  regionBoundary: Box
)

/** Figure that has been rendered to a buffered image.
  *
  * `imageRegion` specifies the exact, inclusive, integer pixel coordinates the bufferedImage
  * occupies within the page if the page was rendered at `dpi`. Due to rounding and possible
  * post-rasterization cleanup of the figure region it might not perfectly correspond to
  * simply rescaling figure.regionBoundary
  */
case class RasterizedFigure(
  figure: Figure,
  imageRegion: Box,
  bufferedImage: BufferedImage,
  dpi: Int
) {
  require(
    imageRegion.x1 % 1 == 0 && imageRegion.x2 % 1 == 0 && imageRegion.y1 % 1 == 0 &&
      imageRegion.x2 % 1 == 0,
    "imageRegion must have integer coordinates"
  )
}

object SavedFigure {
  def apply(figure: RasterizedFigure, renderUrl: String): SavedFigure = {
    val fig = figure.figure
    SavedFigure(
      fig.name,
      fig.figType,
      fig.page,
      fig.caption,
      fig.imageText,
      fig.captionBoundary,
      figure.imageRegion.scale(72.0 / figure.dpi),
      renderUrl,
      figure.dpi
    )
  }
  def apply(figure: Figure, renderUrl: String, renderDpi: Int): SavedFigure = {
    SavedFigure(
      figure.name,
      figure.figType,
      figure.page,
      figure.caption,
      figure.imageText,
      figure.captionBoundary,
      figure.regionBoundary,
      renderUrl,
      renderDpi
    )
  }
}

/** Figure that has been saved to a given URL */
case class SavedFigure(
  name: String,
  figType: FigureType,
  page: Int,
  caption: String,
  imageText: Seq[String],
  captionBoundary: Box,
  regionBoundary: Box,
  renderURL: String,
  renderDpi: Int
)

case class FiguresInDocument(figures: Seq[Figure], failedCaptions: Seq[Caption])

case class RasterizedFiguresInDocument(figures: Seq[RasterizedFigure], failedCaptions: Seq[Caption])
