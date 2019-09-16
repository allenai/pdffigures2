package org.allenai.pdffigures2

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.{ ImageType, PDFRenderer }

import java.awt.image.BufferedImage

import scala.collection.mutable

/** Finds the bounding boxes of graphical elements in a PDF by rasterizing the PDF and
  * finding the the bounding boxes of the connected components in the image.
  * Currently only used for OCRed PDFs.
  */
object FindGraphicsRaster {

  // Pixels that are lighter then this threshold are ignored
  private val Threshold = 240

  // DPI to render the image at, in practice sub-72 seems to risk pixels being lost
  private val DPI = 72
  require(72 % DPI == 0, "Currently need an integer scaling factor relative to 72 DPI")

  def findCCBoundingBoxes(doc: PDDocument, page: Int, remove: Iterable[Box]): List[Box] = {
    val renderer = new PDFRenderer(doc)
    val img = renderer.renderImageWithDPI(page, DPI, ImageType.GRAY)
    findCCBoundingBoxes(img, remove, Threshold, DPI / 72)
  }

  private def findCCBoundingBoxes(
    image: BufferedImage,
    remove: Iterable[Box],
    grayScaleTresh: Int,
    rescale: Int
  ): List[Box] = {
    val h = image.getHeight
    val w = image.getWidth
    val pixels: Array[Int] = new Array[Int](w * h)
    image.getRaster.getPixels(0, 0, w, h, pixels)
    remove.foreach { box =>
      for (y <- Math.floor(box.y1 / rescale).toInt to
             Math.min(Math.ceil(box.y2 / rescale).toInt, h)) {
        for (x <- Math.floor(box.x1 / rescale).toInt to
               Math.min(Math.ceil(box.x2 / rescale).toInt, w)) {
          pixels(w * y + x) = grayScaleTresh
        }
      }
    }
    findCCBoundingBoxes(pixels, w, h, grayScaleTresh, rescale)
  }

  private def findCCBoundingBoxes(
    pixels: Array[Int],
    w: Int,
    h: Int,
    pixThreshold: Int,
    rescale: Int
  ): List[Box] = {
    val pixelsToExplore = mutable.Set[Int]()

    var boundingBoxes = List[Box]()
    for (y <- 0 until h) {
      for (x <- 0 until w) {
        val pixelIndex = x + y * w
        if (pixels(pixelIndex) < pixThreshold) {
          var minX = x
          var maxX = x
          var minY = y
          var maxY = y
          pixelsToExplore.add(pixelIndex)
          while (pixelsToExplore.nonEmpty) {
            val currentPixel = pixelsToExplore.head
            pixelsToExplore.remove(currentPixel)
            if (currentPixel > w) {
              val lowerPixel = currentPixel - w
              if (pixels(lowerPixel) < pixThreshold) {
                pixelsToExplore.add(currentPixel - w)
                minY = Math.min(minY, lowerPixel / w)
              }
            }
            if (currentPixel < pixels.length - w) {
              val upperPixel = currentPixel + w
              if (pixels(upperPixel) < pixThreshold) {
                pixelsToExplore.add(upperPixel)
                maxY = Math.max(maxY, upperPixel / w)
              }
            }
            if (currentPixel % w != 0) {
              val leftPixel = currentPixel - 1
              if (pixels(leftPixel) < pixThreshold) {
                pixelsToExplore.add(leftPixel)
                minX = Math.min(minX, leftPixel % w)
              }
            }
            if ((currentPixel + 1) % w != 0) {
              val rightPixel = currentPixel + 1
              if (pixels(rightPixel) < pixThreshold) {
                pixelsToExplore.add(rightPixel + 1)
                maxX = Math.max(maxX, rightPixel % w)
              }
            }
            // Set the current pixel to white so we don't visit it again.
            pixels(currentPixel) = pixThreshold
          }
          boundingBoxes = new Box(minX * rescale, minY * rescale, maxX * rescale, maxY * rescale) :: boundingBoxes
        }
      }
    }
    boundingBoxes
  }
}
