package org.allenai.pdffigures2

object FigureDetector {

  private val MinProposalHeight = 15
  private val MinProposalWidth = 20

  // Used when clustering/clipping proposals
  private val ClipRegionMinGraphicSize = 5000
  private val ClipRegionLargeTextSize = 3000
  private val ClipRegionMaxTextDistance = 4
  private val ClipRegionMaxLargeTextDistance = 10
  private val ClipRegionMaxGraphicDistance = 20

  // Use for scoring regions, which effects which of the proposed regions we generated will be
  // used, change with care since they have already been loosely tuned on the evaluation datasets
  private val LeftRightFigurePenalty = 0.75
  private val SplitDifferentTypesPenalty = 0.75
  private val SplitSameTypesPenalty = 0.5
  private val SplitWhitespaceBonus = 2
  private val LargeGraphicThreshold = 2000
  private val ContainsGraphicBonus = 0.1
  private val ContainsLargeGraphicBonus = 0.15

  private val SplitVerticalRegionMinHeightFraction = 4

  // Used for filtering out proposals that are likely to be wrong because they cut across figure
  // elements or because they are too close to the page boundary
  val cutFilterIntervalMin = 0.1
  val cutFilterIntervalMax = 0.9
  val boundaryFilterMinDistance = 30

  sealed private trait ProposalDirection
  private object ProposalDirection {
    case object Up extends ProposalDirection
    case object Left extends ProposalDirection
    case object Down extends ProposalDirection
    case object Right extends ProposalDirection
  }

  /** Proposed region of the document that could contain a figure */
  private case class Proposal(
    region: Box,
    caption: CaptionParagraph,
    dir: ProposalDirection,
    splitWith: Option[(CaptionParagraph, Box)] = None
  )

  /** Remove text that is contained within `regions` */
  private def removeTextInRegions(
    paragraphs: List[Paragraph],
    regions: List[Box]
  ): List[Paragraph] = {
    if (regions.isEmpty) {
      paragraphs
    } else {
      val cleanedParagraphs =
        paragraphs.flatMap { p =>
          // TODO consider pruning word by word
          val filteredLines = p.lines.filter(l => !regions.exists(_.contains(l.boundary)))
          if (filteredLines.nonEmpty) {
            Some(Paragraph(filteredLines))
          } else {
            None
          }
        }
      cleanedParagraphs
    }
  }

  /** Rough attempt at finding the x1,x2 coordinates of the whitespace column
    * in a two column page. Doing this right turns out to be important since it
    * affects how we propose canddiate regions
    */
  private def findCenterColumn(page: PageWithBodyText): (Double, Double) = {
    val textCenter = Box
      .container((page.bodyText ++ page.otherText).map(_.boundary) ++ page.captions.map(_.boundary))
      .xCenter

    // Try to guess the column's based on the body text, but fall back on `textCenter` if
    // that does not appear to be working
    val leftSide = page.bodyText.filter(p => p.boundary.x2 < textCenter)
    val rightSide = page.bodyText.filter(p => p.boundary.x1 > textCenter)
    val centerX1 = if (leftSide.isEmpty) {
      textCenter
    } else {
      val leftX2 = leftSide.map(_.boundary.x2).max
      if (Math.abs(leftX2 - textCenter) < 10) {
        leftX2
      } else {
        textCenter
      }
    }
    val centerX2 = if (rightSide.isEmpty) {
      textCenter
    } else {
      val rightX1 = rightSide.map(_.boundary.x1).min
      if (Math.abs(rightX1 - textCenter) < 10) {
        rightX1
      } else {
        textCenter
      }
    }
    (centerX1, centerX2)
  }

  /** Tries to split `proposalRegion` horizontally into two proposal regions
    *
    * We use a simple heuristic based on finding the largest block of whitespace between the two
    * regions.
    *
    * @param proposalRegion to split
    * @param content bounding regions of the content of the page
    * @return None if no split was found, else
    *         Some((upperRegion, lowerRegion, seperatingWhitespace))
    */
  private def splitRegionHorizontally(
    proposalRegion: Box,
    content: Seq[Box]
  ): Option[(Box, Box, Box)] = {
    val intersects = content.filter(_.intersects(proposalRegion))
    val emptyBlocks = Box.findEmptyHorizontalBlocks(proposalRegion, intersects)
    val emptyBlocksNearCenter = emptyBlocks.filter(
      e =>
        (e.y1 - proposalRegion.y1) > proposalRegion.height / SplitVerticalRegionMinHeightFraction &&
          (proposalRegion.y2 - e.y2) > proposalRegion.height /
            SplitVerticalRegionMinHeightFraction && e.height > 2
    )
    if (emptyBlocksNearCenter.isEmpty) {
      None
    } else {
      val largest = emptyBlocksNearCenter.maxBy(_.area)
      val upper = Box.crop(proposalRegion.copy(y2 = largest.y1), intersects, -1)
      val lower = Box.crop(proposalRegion.copy(y1 = largest.y2), intersects, -1)
      if (lower.isDefined && upper.isDefined &&
          lower.get.height > MinProposalHeight && upper.get.height > MinProposalHeight) {
        Some((upper.get, lower.get, largest))
      } else {
        None
      }
    }
  }

  /** Given a sequence of proposal, attempt to split up any proposals that overlap into
    * non-overlapping proposals.
    */
  private def splitProposals(proposals: Seq[Proposal], content: Seq[Box]): Seq[Proposal] = {

    // Group the proposals into groups that overlap with each other
    var groupedByCollision = Seq[Seq[Proposal]]()
    var proposalToCheck = proposals
    while (proposalToCheck.nonEmpty) {
      // TODO This does not handle some cases where more than 2 figures collide nicely
      val (collides, rest) = proposalToCheck.tail.partition(
        _.region.intersects(proposalToCheck.head.region, -2)
      )
      groupedByCollision = (proposalToCheck.head +: collides) +: groupedByCollision
      proposalToCheck = rest
    }

    // Try to split any overlapping regions to handle cases of adjacent figures, currently we are
    // very conservative in what we attempt to split, but this seems to cover most cases
    val splitProposals = groupedByCollision.flatMap { group =>
      if (group.size == 1) {
        group
      } else {
        if (group.size == 2 && group.exists(_.dir == ProposalDirection.Up) &&
            group.exists(_.dir == ProposalDirection.Down) &&
            (group.last.region.contains(group.head.region) ||
            group.head.region.contains(group.last.region))) {
          val region = Box.container(group.map(_.region))
          val splitAttempt = splitRegionHorizontally(region, content)
          if (splitAttempt.isDefined) {
            val (upperSplit, lowerSplit, whiteSpace) = splitAttempt.get
            val (upperProp, lowerProp) = group.partition(_.dir == ProposalDirection.Down)
            Seq(
              upperProp.head.copy(
                region = upperSplit,
                splitWith = Some((lowerProp.head.caption, whiteSpace))
              ),
              lowerProp.head.copy(
                region = lowerSplit,
                splitWith = Some((upperProp.head.caption, whiteSpace))
              )
            )
          } else {
            group
          }
        } else {
          group
        }
      }
    }
    splitProposals
  }

  /** Yields the cross product of a list of lists, from
    * http://stackoverflow.com/questions/8217764/cartesian-product-of-two-lists
    */
  def cartesianProduct[T](xss: List[List[T]]): List[List[T]] = xss match {
    case Nil => List(Nil)
    case h :: t => for (xh <- h; xt <- cartesianProduct(t)) yield xh :: xt
  }

  /** If a caption does not cross page's center, and the proposal does not contain any elements
    * that cross the page's center, crop the proposal region so that it also does not cross the
    * center
    */
  private def cropToCenter(caption: Box, proposal: Box, inCenter: Seq[Box], center: Double): Box = {
    val captionCrossesCenter = caption.x2 > center && caption.x1 <= center
    val proposalCrossesCenter = proposal.x2 > center && proposal.x1 < center
    if (proposalCrossesCenter && !captionCrossesCenter) {
      if (inCenter.forall(!proposal.contains(_))) {
        if (caption.x1 > center) {
          proposal.copy(x1 = center)
        } else {
          proposal.copy(x2 = center)
        }
      } else {
        proposal
      }
    } else {
      proposal
    }
  }

  /** Performs a clustering step, where if we find a large graphic region in a proposal we try to
    * cluster elements around that region and then 'clip' any elements outside of that cluster
    * from the proposal. This provides some robustness to misclassified body text.
    *
    * @return the clipped proposal
    */
  // TODO it would be nice to be able to do this for downwards proposals as well
  private def clipUpwardRegion(
    caption: Box,
    region: Box,
    graphics: Seq[Box],
    otherText: Seq[Paragraph]
  ): Box = {
    val containedGraphics = graphics.filter(g => region.intersectArea(g) / g.area > 0.95)
    val (significantGraphics, nonSigGraphics) =
      containedGraphics.partition(g => g.area > ClipRegionMinGraphicSize)
    if (significantGraphics.nonEmpty) {
      var cluster = Box.container(significantGraphics)
      var remainingGraphics = nonSigGraphics
      var remainingOtherText = otherText.filter(p => region.intersects(p.boundary))
      var done = false
      while (!done) {
        val (inClusterText, outOfClusterText) =
          remainingOtherText.partition { p =>
            val yDist = cluster.y1 - p.boundary.y2
            if (p.boundary.area < ClipRegionLargeTextSize) {
              yDist < ClipRegionMaxLargeTextDistance
            } else {
              yDist < ClipRegionMaxTextDistance
            }
          }
        val (inClusterGraphics, outOfClusterGraphics) = remainingGraphics.partition { g =>
          val yDist = cluster.y1 - g.y2
          yDist < ClipRegionMaxGraphicDistance
        }
        remainingGraphics = outOfClusterGraphics
        remainingOtherText = outOfClusterText
        val newBoxes = inClusterGraphics ++ inClusterText.map(_.boundary)
        if (newBoxes.nonEmpty) {
          cluster = Box.container(cluster +: newBoxes).intersectRegion(region).get
        } else {
          done = true
        }
      }
      cluster
    } else {
      region
    }
  }

  private def scoreProposal(
    proposal: Proposal,
    graphics: Seq[Box],
    otherText: Seq[Box],
    otherProposals: Seq[Proposal],
    bounds: Box
  ): Option[Double] = {
    val boundary = proposal.region
    if (otherProposals.exists(p => p.region.intersects(boundary, -2))) {
      None
    } else {
      var areaScore = boundary.area / bounds.area
      if (graphics.exists(g => boundary.contains(g))) {
        areaScore += ContainsGraphicBonus
      }
      if (graphics.exists(g => g.area > LargeGraphicThreshold && boundary.contains(g))) {
        areaScore += ContainsLargeGraphicBonus
      }
      if (proposal.splitWith.isDefined) {
        val (splitWith, whiteSpace) = proposal.splitWith.get
        areaScore += whiteSpace.area * SplitWhitespaceBonus / bounds.area
        if (splitWith.figType != proposal.caption.figType) {
          areaScore *= SplitDifferentTypesPenalty
        } else {
          areaScore *= SplitSameTypesPenalty
        }
      } else if (proposal.dir == ProposalDirection.Left ||
                 proposal.dir == ProposalDirection.Right) {
        areaScore *= LeftRightFigurePenalty
      }
      Some(areaScore)
    }
  }

  /** Decide if box1 is to the left, right, or horizontally overlaps box2 and whether it is
    * above, below, or overlaps box2 vertically
    */
  private def boxAlignment(box1: Box, box2: Box): (Int, Int) = {
    val h = if (box1.x2 < box2.x1) {
      -1
    } else if (box1.x1 > box2.x2) {
      1
    } else {
      0
    }
    val v = if (box1.y2 < box2.y1) {
      -1
    } else if (box1.y1 > box2.y2) {
      1
    } else {
      0
    }
    (h, v)
  }

  /** Expand `box` horizontally as far as possible without intersecting `boxes` or exceeding
    * `bounds`
    */
  private def boxExpandLR(box: Box, boxes: Seq[Box], bounds: Box): Box = {
    var x1 = bounds.x1
    var x2 = bounds.x2
    boxes.foreach { box2 =>
      val (h, v) = boxAlignment(box, box2)
      if (v == 0) {
        if (h == 1) {
          x1 = Math.max(x1, box2.x2)
        } else if (h == -1) {
          x2 = Math.min(x2, box2.x1)
        }
      }
    }
    box.copy(x1 = x1, x2 = x2)
  }

  /** Expand `box` vertically as far as possible without intersecting `boxes` or going
    * past `bounds`
    */
  private def boxExpandUD(box: Box, boxes: Seq[Box], bounds: Box): Box = {
    var y1 = bounds.y1
    var y2 = bounds.y2
    boxes.foreach { box2 =>
      val (h, v) = boxAlignment(box, box2)
      if (h == 0) {
        if (v == 1) {
          y1 = Math.max(y1, box2.y2)
        } else if (v == -1) {
          y2 = Math.min(y2, box2.y1)
        }
      }
    }
    box.copy(y1 = y1, y2 = y2)
  }

  /** For each caption in `page`, builds a (possibly empty) sequence of Proposals for that
    * caption
    */
  private def buildProposals(
    page: PageWithBodyText,
    layout: DocumentLayout
  ): Seq[List[Proposal]] = {
    val nonFigureContent = page.nonFigureContent
    val possibleFigureContent = page.possibleFigureContent
    val allContent = nonFigureContent ++ possibleFigureContent
    val bounds = Box.container(allContent)

    val otherTextWordsBBs = page.otherText.flatMap { paragraph =>
      paragraph.lines.flatMap(
        line =>
          line.words.map(
            word =>
              // PDFBox can hugely overestimate word height at times, so we clip to a height of 5 if a box
              // was given a really large height. We use a very conservative setting here since we will
              // use these bounding boxes to filter candidate regions
              if (word.boundary.height < 10) {
                word.boundary
              } else {
                word.boundary.copy(y1 = word.boundary.y2 - 5.0f)
              }
          )
      )
    }

    val twoColumn = layout.twoColumns
    val centerColumn = if (twoColumn) {
      Some(findCenterColumn(page))
    } else {
      None
    }
    val pageCenter = centerColumn match {
      case Some((x1, x2)) => Some((x1 + x2) / 2.0)
      case None => None
    }
    val crossesCenter = centerColumn match {
      case Some((x1, x2)) => possibleFigureContent.filter(box => box.x1 < x1 && box.x2 > x2)
      case None => Seq()
    }

    // For each caption, build a set of proposed figure areas
    val proposalsPerCaption = page.captions.map { caption =>
      val captBox = caption.boundary
      // Figure out the maximum we could expand the caption's bounding box without intersecting a
      // non-figure element in each direction
      var (x1, y1, x2, y2) = (bounds.x1, bounds.y1, bounds.x2, bounds.y2)
      nonFigureContent.foreach { box =>
        val (h, v) = boxAlignment(captBox, box)
        if (h == 0) {
          if (v == 1) {
            y1 = Math.max(y1, box.y2)
          } else if (v == -1) {
            y2 = Math.min(box.y1, y2)
          }
        } else if (v == 0) {
          if (h == 1) {
            x1 = Math.max(x1, box.x2)
          } else if (h == -1) {
            x2 = Math.min(x2, box.x1)
          }
        }
      }

      var proposals = List[Proposal]()
      if (x1 < captBox.x1 - MinProposalWidth) {
        val prop = boxExpandUD(
          captBox.copy(x1 = x1),
          nonFigureContent,
          bounds
        ).copy(x2 = captBox.x1)
        if (twoColumn) {
          val containsCenterElement = crossesCenter.exists(prop.contains(_, 2))
          if (containsCenterElement ||
              !(prop.x1 < pageCenter.get && prop.x2 > pageCenter.get)) {
            proposals = Proposal(prop, caption, ProposalDirection.Left) :: proposals
          }
        } else {
          proposals = Proposal(prop, caption, ProposalDirection.Left) :: proposals
        }
      }
      if (x2 > captBox.x2 + MinProposalWidth) {
        val prop = boxExpandUD(
          captBox.copy(x2 = x2),
          nonFigureContent,
          bounds
        ).copy(x1 = captBox.x2)
        if (twoColumn) {
          val containsCenterElement = crossesCenter.exists(prop.contains(_, 2))
          if (containsCenterElement ||
              !(prop.x1 < pageCenter.get && prop.x2 > pageCenter.get)) {
            proposals = Proposal(prop, caption, ProposalDirection.Right) :: proposals
          }
        } else {
          proposals = Proposal(prop, caption, ProposalDirection.Right) :: proposals
        }
      }
      if (y1 < captBox.y1 - MinProposalHeight) {
        val prop = boxExpandLR(
          captBox.copy(y1 = y1),
          nonFigureContent,
          bounds
        ).copy(y2 = captBox.y1)
        val cropped = if (twoColumn) {
          cropToCenter(captBox, prop, crossesCenter, pageCenter.get)
        } else {
          prop
        }
        val pruned = clipUpwardRegion(captBox, cropped, page.graphics, page.otherText)
        proposals = Proposal(pruned, caption, ProposalDirection.Up) :: proposals
      }
      if (y2 > captBox.y2 + MinProposalHeight) {
        val prop = boxExpandLR(
          captBox.copy(y2 = y2),
          nonFigureContent,
          bounds
        ).copy(y1 = captBox.y2)
        val cropped = if (twoColumn) {
          cropToCenter(captBox, prop, crossesCenter, pageCenter.get)
        } else {
          prop
        }
        proposals = Proposal(cropped, caption, ProposalDirection.Down) :: proposals
      }

      proposals = proposals.flatMap { prop =>
        // -1 so we do not count boxes the overlap exactly on the border
        Box.crop(prop.region, allContent, -1) match {
          case Some(cropped)
              if cropped.width > MinProposalWidth &&
                cropped.height > MinProposalHeight =>
            val partiallyIntersectsWord =
              otherTextWordsBBs.exists(b => b.intersects(cropped, -2) && !cropped.contains(b, 1))
            if (partiallyIntersectsWord) {
              None
            } else {
              Some(prop.copy(region = cropped))
            }
          case _ => None
        }
      }
      proposals
        .filter((proposal: Proposal) => !boxCutsFigure(proposal.region, possibleFigureContent))
        .filter((proposal: Proposal) => !boxOnBoundary(proposal.region))
    }
    proposalsPerCaption
  }

  /** Detects figure proposals that are too close to the top and left page boundaries */
  // TODO noahs: We should find a way to get page width and height from PDFBox so that we can check
  // the bottom and right boundaries as well
  private def boxOnBoundary(box: Box): Boolean = {
    box.x1 <= boundaryFilterMinDistance || box.y1 <= boundaryFilterMinDistance
  }

  private def inCutInterval(d: Double) = { d >= cutFilterIntervalMin && d <= cutFilterIntervalMax }

  /** Detects figure proposals whose boundary cuts accross figure elements */
  private def boxCutsFigure(box: Box, possibleFigureContent: Seq[Box]): Boolean = {
    possibleFigureContent.map((fig: Box) => fig.intersectArea(box) / fig.area).exists(inCutInterval)
  }

  /** Attempts to build a Figure for each caption in 'PageWithRegions' */
  def locatedFigures(
    page: PageWithBodyText,
    layout: DocumentLayout,
    log: Option[VisualLogger]
  ): PageWithFigures = {
    val proposals = buildProposals(page, layout)
    val proposalsWithCaptions = page.captions.zip(proposals)

    val allContent = page.allContent
    val bounds = Box.container(allContent)

    val captionsWithNoProposals = proposalsWithCaptions.filter(_._2.isEmpty).map(_._1)
    val validProposals = proposalsWithCaptions.map(_._2).filter(_.nonEmpty)
    val configurationCount = validProposals.map(_.size.toLong).product
    // For some papers, we end up with billions of configurations. We don't have time to evaluate
    // them, so we give up when there are more than 500k.
    if (validProposals.isEmpty || configurationCount > 500000) {
      PageWithFigures(
        page.pageNumber,
        (page.otherText ++ page.bodyText ++ captionsWithNoProposals.map(_.paragraph)).sorted.toList,
        page.classifiedText,
        List(),
        captionsWithNoProposals.map(Caption.apply)
      )
    } else {
      val bestConfiguration = cartesianProduct(validProposals.toList).view.zipWithIndex
        .map {
          case (proposalsToUse, index) =>
            var props = splitProposals(proposalsToUse, allContent).toList
            var scored = List[Proposal]()
            var scores = List[Option[Double]]()
            while (props.nonEmpty) {
              val prop = props.head
              props = props.tail
              val score = scoreProposal(
                prop,
                page.graphics,
                page.otherText.map(_.boundary),
                scored ::: props,
                bounds
              )
              scored = prop :: scored
              scores = score :: scores
            }
            val overallScore = scores.flatMap(x => x).sum - scores.count(_.isEmpty)
            (overallScore, scored.zip(scores).reverse)
        }
        .maxBy(_._1)

      val (goodProps, badProps) = bestConfiguration._2.partition(_._2.isDefined)
      val figures = goodProps.map {
        case (proposal, _) =>
          val imageText = page.otherText.flatMap(
            p =>
              p.lines.flatMap { l =>
                l.words.flatMap {
                  case word =>
                    if (proposal.region.contains(word.boundary, 1)) Some(word.text) else None
                }
              }
          )
          val caption = proposal.caption
          Figure(
            caption.name,
            caption.figType,
            caption.page,
            caption.text,
            imageText,
            caption.boundary,
            proposal.region
          )
      }
      val failedCaptions = badProps.map(_._1.caption) ++ captionsWithNoProposals
      val nonFigureText = removeTextInRegions(
        (page.otherText ++ page.bodyText).toList,
        figures.map(_.regionBoundary)
      ) ++ failedCaptions.map(_.paragraph)
      PageWithFigures(
        page.pageNumber,
        nonFigureText.sorted,
        page.classifiedText,
        figures,
        failedCaptions.map(Caption.apply)
      )
    }
  }
}
