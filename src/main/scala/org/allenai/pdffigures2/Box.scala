package org.allenai.pdffigures2

import org.apache.pdfbox.pdmodel.common.PDRectangle

object Box {

  def fromPDRect(rect: PDRectangle): Box = {
    Box(rect.getLowerLeftX, rect.getLowerLeftY, rect.getUpperRightX, rect.getUpperRightY)
  }

  /** @return Box that contains all Boxes in `boxes`
    */
  def container(boxes: Iterable[Box]): Box = {
    require(boxes.nonEmpty, "Cannot find a container of an empty list")
    val head = boxes.head
    var minX = head.x1
    var minY = head.y1
    var maxX = head.x2
    var maxY = head.y2
    boxes.tail.foreach { box =>
      minX = Math.min(minX, box.x1)
      minY = Math.min(minY, box.y1)
      maxX = Math.max(maxX, box.x2)
      maxY = Math.max(maxY, box.y2)
    }
    Box(minX, minY, maxX, maxY)
  }

  /** @return Box contained by `box` that has been cropped to `boxes`, meaning it has been shrunk
    *   as much as possible providing it still intersects `boxes` in the same places as before,
    *   returns None if `box` did not contain any box in `boxes`
    */
  def crop(box: Box, boxes: Iterable[Box], tol: Double = 0): Option[Box] = {
    var shrinkLeft = box.width
    var shrinkRight = box.width
    var shrinkUp = box.height
    var shrinkDown = box.height
    var foundAny = false
    boxes.foreach { other =>
      if (other.intersects(box, tol)) {
        shrinkLeft = Math.min(shrinkLeft, other.x1 - box.x1)
        shrinkRight = Math.min(shrinkRight, box.x2 - other.x2)
        shrinkUp = Math.min(shrinkUp, box.y2 - other.y2)
        shrinkDown = Math.min(shrinkDown, other.y1 - box.y1)
        foundAny = true
      }
    }
    if (foundAny) {
      Some(
        Box(
          box.x1 + Math.max(shrinkLeft, 0),
          box.y1 + Math.max(shrinkDown, 0),
          box.x2 - Math.max(shrinkRight, 0),
          box.y2 - Math.max(shrinkUp, 0)
        )
      )
    } else {
      None
    }
  }

  /** Clusters boxes by merging intersecting boxes until no boxes intersect within a given tolerance
    *
    * @param boxes to merge
    * @param tol to use when calculating intersections, returned boxes will be at least `tol`
    *            manhattan distance from each other, can be negative.
    */
  def mergeBoxes(boxes: List[Box], tol: Double): List[Box] = {
    if (boxes.isEmpty) {
      boxes
    } else {
      // We iteratively pick a Box that intersects at least one other box and replace the
      // intersecting box with a Box containing them
      var currentBoxes = boxes
      var foundIntersectingBoxes = true
      while (foundIntersectingBoxes) {
        foundIntersectingBoxes = false

        // The box we are going to check to see if there are any intersecting boxes, followed by
        // any boxes that we have already check
        var checked = List(currentBoxes.head)
        var unchecked = currentBoxes.tail

        while (!foundIntersectingBoxes && unchecked.nonEmpty) {
          val (intersects, nonIntersects) = unchecked.partition(_.intersects(checked.head, tol))
          if (intersects.nonEmpty) {
            val newBox = Box.container(checked.head :: intersects)
            currentBoxes = nonIntersects ::: (newBox :: checked.tail)
            foundIntersectingBoxes = true // Exit this loop and re-enter the outer loop
          } else {
            checked = unchecked.head :: checked
            unchecked = unchecked.tail
          }
        }
      }
      currentBoxes
    }
  }

  /** @return all Boxes that can built such that they have the same x1 and x2 as `box`, they are
    *   contained within `box`, and do not intersect any box in `contents`. Returned boxes
    *   are maximally expanded, therefore they will not intersect or have overlapping borders
    */
  def findEmptyHorizontalBlocks(box: Box, contents: Seq[Box]): Seq[Box] = {
    // We keep a list of empty horizontal boxes starting with `box` itself, and for each
    // element in `content` we split or crop boxes in our list that intersect that element
    contents.foldLeft(List(box)) {
      case (emptyBlocks, contentBox) =>
        emptyBlocks.flatMap { emptyRegion =>
          if (contentBox.intersects(emptyRegion)) {
            if (contentBox.y1 <= emptyRegion.y1) {
              if (contentBox.y2 < emptyRegion.y2) {
                Some(emptyRegion.copy(y1 = contentBox.y2))
              } else {
                None
              }
            } else if (contentBox.y2 >= emptyRegion.y2) {
              if (contentBox.y1 > emptyRegion.y1) {
                Some(emptyRegion.copy(y2 = contentBox.y1))
              } else {
                None
              }
            } else {
              Seq(
                emptyRegion.copy(y2 = contentBox.y1),
                emptyRegion.copy(y1 = contentBox.y2)
              )
            }
          } else {
            Some(emptyRegion)
          }
        }
    }
  }
}

case class Box(x1: Double, y1: Double, x2: Double, y2: Double) {
  // We explicitly allow 0 width/height since PDFBox can return zero area boundaries
  // for valid pieces of text
  require(x1 <= x2, "boxes must have width >= 0")
  require(y1 <= y2, "boxes must have height >= 0")

  def width: Double = x2 - x1
  def height: Double = y2 - y1
  def xCenter: Double = (x2 + x1) / 2
  def yCenter: Double = (y2 + y1) / 2
  def area: Double = width * height

  def scale(scale: Double): Box = Box(x1 * scale, y1 * scale, x2 * scale, y2 * scale)

  def horizontallyAligned(other: Box, tol: Double): Boolean =
    !(other.x1 - tol > x2 || other.x2 + tol < x1)

  def yDistanceTo(other: Box): Double = Math.min(Math.abs(other.y1 - y2), Math.abs(y2 - other.y1))

  def intersects(other: Box, tol: Double = 0): Boolean =
    !((x2 < other.x1 - tol) || (x1 > other.x2 + tol) ||
      (y2 < other.y1 - tol) || (y1 > other.y2 + tol))

  def intersectRegion(other: Box): Option[Box] = {
    if (!intersects(other)) {
      None
    } else {
      val overlapX1 = Math.max(x1, other.x1)
      val overlapY1 = Math.max(y1, other.y1)
      val overlapX2 = Math.min(x2, other.x2)
      val overlapY2 = Math.min(y2, other.y2)
      Some(Box(overlapX1, overlapY1, overlapX2, overlapY2))
    }
  }

  def intersectArea(other: Box): Double = {
    intersectRegion(other) match {
      case Some(overlapRegion) => overlapRegion.area
      case None => 0.0f
    }
  }

  def intersectsAny(other: Seq[Box], tol: Double = 0): Boolean = other.exists(intersects(_, tol))

  def contains(other: Box, tol: Double = 0): Boolean =
    (x1 <= other.x1 + tol) && (y1 <= other.y1 + tol) &&
      (x2 >= other.x2 - tol) && (y2 >= other.y2 - tol)

  def container(other: Box): Box = {
    val minX = Math.min(x1, other.x1)
    val minY = Math.min(y1, other.y1)
    val maxX = Math.max(x2, other.x2)
    val maxY = Math.max(y2, other.y2)
    Box(minX, minY, maxX, maxY)
  }
}
