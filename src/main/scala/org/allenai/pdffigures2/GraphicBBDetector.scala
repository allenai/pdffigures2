package org.allenai.pdffigures2

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine
import org.apache.pdfbox.contentstream.operator.Operator
import org.apache.pdfbox.contentstream.operator.OperatorProcessor
import org.apache.pdfbox.cos.{ COSBase, COSName }
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.graphics.color.PDColor
import org.apache.pdfbox.util.Matrix
import org.apache.pdfbox.pdmodel.graphics.image.PDImage
import org.apache.pdfbox.pdmodel.graphics.state.PDSoftMask

import java.awt.Rectangle
import java.awt.geom._

/** Based on PageDrawer.java, this attempts to infer the bounding boxes of all graphical elements
  * on a PDPage by parsing the PDF operators
  */
object GraphicBBDetector {

  private val EmptyPattern = new PDColor(Array[Float](), null)
  def isWhite(color: PDColor): Boolean =
    !color.isPattern && color.toRGB == 16777215 || color.equals(EmptyPattern)

  def findGraphicBB(page: PDPage, ignoreWhite: Boolean): List[Box] = {
    val detector = new GraphicBBDetector(page, ignoreWhite)
    val cropBox = page.getCropBox
    val h = cropBox.getHeight
    // Note we currently skip annotations (see PDFBox's `PageDrawer.java`)
    detector.processPage(page)
    val asBoxes = detector.bounds.map { r =>
      new Box(
        r.x - cropBox.getLowerLeftX,
        h - r.y - r.height + cropBox.getLowerLeftY,
        r.x + r.width - cropBox.getLowerLeftX,
        h - r.y + cropBox.getLowerLeftY
      )
    }
    if (page.getRotation == 270) {
      asBoxes.map(box => box.copy(y1 = box.x1, y2 = box.x2, x1 = box.y1, x2 = box.y2))
    } else if (page.getRotation == 90) {
      asBoxes.map(box => box.copy(y1 = box.x1, y2 = box.x2, x1 = h - box.y2, x2 = h - box.y1))
    } else if (page.getRotation == 180) {
      asBoxes.map(box => box.copy(y1 = h - box.y2, y2 = h - box.y1))
    } else {
      asBoxes
    }
  }
}

class GraphicBBDetector(page: PDPage, ignoreWhite: Boolean) extends PDFGraphicsStreamEngine(page) {
  var clipWindingRule: Int = -1
  var linePath: GeneralPath = new GeneralPath
  var bounds = List[Rectangle]()

  class NullOp(val name: String) extends OperatorProcessor {
    override def process(operator: Operator, operands: java.util.List[COSBase]): Unit = {}
    def getName: String = name
  }

  // If we ignore colors, fonts, or text, we tell our super class to skip those operators
  // since they can be computationally expensive. Since is does not look like we can remove
  // them the hacky solution for now is to override the existing ones with null operators
  if (!ignoreWhite) {
    addOperator(new NullOp("d"))
    addOperator(new NullOp("k"))
    addOperator(new NullOp("K"))
    addOperator(new NullOp("g"))
    addOperator(new NullOp("G"))
    addOperator(new NullOp("CS"))
    addOperator(new NullOp("cs"))
    addOperator(new NullOp("RG"))
    addOperator(new NullOp("rg"))
    addOperator(new NullOp("sc"))
    addOperator(new NullOp("SC"))
    addOperator(new NullOp("scn"))
    addOperator(new NullOp("SCN"))
  }

  // Ignore text and font ops:
  addOperator(new NullOp("Tf"))
  addOperator(new NullOp("Tj"))
  addOperator(new NullOp("TJ"))
  addOperator(new NullOp("T*"))
  addOperator(new NullOp("'"))
  addOperator(new NullOp("\""))

  override def processOperator(operator: Operator, operands: java.util.List[COSBase]): Unit = {
    if (Thread.interrupted()) throw new InterruptedException()
    super.processOperator(operator, operands)
  }

  override def appendRectangle(p0: Point2D, p1: Point2D, p2: Point2D, p3: Point2D) {
    linePath.moveTo(p0.getX.toFloat, p0.getY.toFloat)
    linePath.lineTo(p1.getX.toFloat, p1.getY.toFloat)
    linePath.lineTo(p2.getX.toFloat, p2.getY.toFloat)
    linePath.lineTo(p3.getX.toFloat, p3.getY.toFloat)
    linePath.closePath()
  }

  private def addLinePath(stroke: Boolean, fill: Boolean): Unit = {
    val newBound =
      getGraphicsState.getCurrentClippingPath.getBounds.intersection(linePath.getBounds)
    if (newBound.getWidth > 0 && newBound.getHeight > 0) {
      val skipWhiteGraphic = ignoreWhite &&
        (!stroke || GraphicBBDetector.isWhite(getGraphicsState.getStrokingColor)) &&
        (!fill || GraphicBBDetector.isWhite(getGraphicsState.getNonStrokingColor))
      if (!skipWhiteGraphic) {
        bounds = newBound :: bounds
      }
    }
  }

  override def strokePath() {
    addLinePath(true, false)
    linePath.reset()
  }

  override def fillPath(windingRule: Int) {
    linePath.setWindingRule(windingRule)
    addLinePath(false, true)
    linePath.reset()
  }

  override def fillAndStrokePath(windingRule: Int) {
    linePath.setWindingRule(windingRule)
    addLinePath(true, true)
    linePath.reset()
  }

  override def clip(windingRule: Int) = clipWindingRule = windingRule

  override def moveTo(x: Float, y: Float) = linePath.moveTo(x, y)

  override def lineTo(x: Float, y: Float) = linePath.lineTo(x, y)

  override def curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) =
    linePath.curveTo(x1, y1, x2, y2, x3, y3)

  override def getCurrentPoint: Point2D = linePath.getCurrentPoint

  override def closePath() = linePath.closePath()

  override def endPath() {
    if (clipWindingRule != -1) {
      linePath.setWindingRule(clipWindingRule)
      getGraphicsState.intersectClippingPath(linePath)
      clipWindingRule = -1
    }
    linePath.reset()
  }

  override def drawImage(pdImage: PDImage) {
    val clipBounds = getGraphicsState.getCurrentClippingPath.getBounds
    if (clipBounds.getHeight * clipBounds.getWidth > 0) {
      val ctm: Matrix = getGraphicsState.getCurrentTransformationMatrix
      val at: AffineTransform = new AffineTransform(ctm.createAffineTransform)
      val softMask: PDSoftMask = getGraphicsState.getSoftMask
      if (softMask != null) {
        at.scale(1, -1)
        at.translate(0, -1)
      } else {
        val width: Int = pdImage.getWidth
        val height: Int = pdImage.getHeight
        at.scale(1.0 / width, -1.0 / height)
        at.translate(0, -height)
      }
      val imgBounds: Rectangle = at
        .createTransformedShape(
          new Rectangle(0, 0, pdImage.getWidth, pdImage.getHeight)
        )
        .getBounds
      val newBound = imgBounds.intersection(clipBounds)
      if (newBound.getWidth > 0 && newBound.getHeight > 0) {
        bounds = newBound :: bounds
      }
    }
  }

  override def shadingFill(shadingName: COSName) {
    val newBound = getGraphicsState.getCurrentClippingPath.getBounds
    if (newBound.getWidth > 0 && newBound.getHeight > 0) {
      bounds = newBound :: bounds
    }
  }
}
