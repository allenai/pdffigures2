package org.allenai.pdffigures2

import org.allenai.pdffigures2.SectionedTextBuilder.DocumentSection
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.event.{ ActionEvent, KeyEvent }
import java.awt._
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import java.io.File
import javax.swing._

case class Annotations(
  boxes: Seq[Box],
  color: Color,
  thickness: Int = 1,
  fill: Boolean = false,
  dashed: Boolean = false
)

object VisualLogger {
  val FigureRegionColor = Color.GREEN
  val GraphicalElementColor = Color.GREEN
  val JointCaptionFigureColor = Color.BLUE
  val GraphicClusterColor = new Color(4033335) // Dark Green
  val NonFigureColor = Color.BLACK
  val OtherTextColor = Color.RED
  val TextLineColor = Color.RED
  val CaptionColor = Color.RED
  val SectionColor = Color.RED
  val ParagraphColor = Color.BLUE
  val CleanedFigureColor = Color.BLACK
}

/** Textual information is rarely sufficient for debugging, so this class provides way to log
  * visualizations of intermediate PDF processing stages and then show them to the user in a
  * unified display once the document has been fully parsed. This class is mutable since logs are
  * internally accumulated. Can only be used for logging the processing of a single document
  */
class VisualLogger(
  logFigures: Boolean,
  logExtraction: Boolean,
  logGraphicClustering: Boolean,
  logCaptions: Boolean,
  logRegions: Boolean,
  logSections: Boolean,
  logCleanFigures: Boolean
) {

  val GraphicsClusterKey = "Graphic Clustering"
  val TextAndGraphicsExtractionKey = "Text and Graphic Extraction"
  val CaptionLocationKey = "Caption Locations"
  val RegionClassificationKey = "Region Classification"
  val FiguresKey = "Figures"
  val CleanedFiguresKey = "Cleaned Figures"
  val SectionsKey = "Sections"

  // Ordered in how they will be shown to the user
  val ReservedKeys = Seq(
    GraphicsClusterKey,
    TextAndGraphicsExtractionKey,
    CaptionLocationKey,
    RegionClassificationKey,
    FiguresKey,
    CleanedFiguresKey,
    SectionsKey
  )

  // Maps: annotation layer name => page number => Annotations for that layer/page
  private var logs: Map[String, Map[Int, Seq[Annotations]]] = Map().withDefault(_ => Map())

  // BufferedImages don't have a copy/clone method so we provide one here
  private def cloneImage(bufferedImage: BufferedImage): BufferedImage = {
    val copy = new BufferedImage(
      bufferedImage.getWidth,
      bufferedImage.getHeight,
      bufferedImage.getType
    )
    copy.setData(bufferedImage.getRaster)
    copy
  }

  def displayVisualLog(doc: PDDocument, dpi: Int): Unit = {
    val pagesToShow = logs.values.flatMap(_.keys).toSet
    val keysToShow = ReservedKeys.filter(logs.contains) ++
      (logs.keySet -- ReservedKeys.toSet)

    val scaling = dpi / 72.0
    if (pagesToShow.nonEmpty) {
      val renderer = new PDFRenderer(doc)
      val visualizationPerPage = pagesToShow.map { pageNum =>
        val pageImg = renderer.renderImageWithDPI(pageNum, dpi)
        val imagesToShow = keysToShow.map { key =>
          val annotations = logs(key).getOrElse(pageNum, Seq())
          val img = cloneImage(pageImg)
          val g = img.getGraphics.asInstanceOf[Graphics2D]
          annotations.foreach { annotation =>
            g.setColor(annotation.color)
            val dash = if (annotation.dashed) Array[Float](2) else null
            g.setStroke(
              new BasicStroke(
                annotation.thickness,
                BasicStroke.CAP_BUTT,
                BasicStroke.JOIN_BEVEL,
                0.0f,
                dash,
                0.0f
              )
            )
            annotation.boxes.foreach { box =>
              if (annotation.fill) {
                // Stop narrow lines disappearing in this mode by making the minimum width/height 3
                val w = Math.max(box.width, 3)
                val h = Math.max(box.height, 3)
                g.fillRect(
                  (box.x1 * scaling).toInt,
                  (box.y1 * scaling).toInt,
                  (w * scaling).toInt,
                  (h * scaling).toInt
                )
              } else {
                g.drawRect(
                  (box.x1 * scaling - 2).toInt,
                  (box.y1 * scaling - 2).toInt,
                  (box.width * scaling + 4).toInt,
                  (box.height * scaling + 4).toInt
                )
              }
            }
          }
          (key, img)
        }

        if (imagesToShow.size == 1) {
          val icon = new ImageIcon(imagesToShow.head._2)
          (pageNum, new JLabel("", icon, 0))
        } else {
          val border = BorderFactory.createLineBorder(Color.BLACK, 2)
          val panel = new JPanel()
          imagesToShow.foreach {
            case (key, image) =>
              val subPanel = new JPanel()
              subPanel.setLayout(new BoxLayout(subPanel, BoxLayout.Y_AXIS))
              subPanel.add(new JLabel(key))
              val icon = new ImageIcon(image)
              val label = new JLabel("", icon, 0)
              label.setBorder(border)
              subPanel.add(label)
              panel.add(subPanel)
          }
          val scrollPanel = new JScrollPane(
            panel,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
          )
          (pageNum, scrollPanel)
        }
      }
      val visualization = if (visualizationPerPage.size == 1) {
        visualizationPerPage.head._2
      } else {
        val tabbedPane = new JTabbedPane()
        visualizationPerPage.toSeq.sortBy(_._1).foreach {
          case (pageNum, comp) =>
            tabbedPane.addTab(s"Page ${pageNum + 1}", comp)
        }
        tabbedPane
      }

      val panel = new JPanel(new BorderLayout())
      panel.add(visualization, BorderLayout.CENTER)
      val frame = new JFrame() {
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE)
        add(panel)
      }

      // So our frame can be closed by hot key, on OSX this means at least Cmd-W works
      val closeKey = KeyStroke.getKeyStroke(
        KeyEvent.VK_W,
        Toolkit.getDefaultToolkit.getMenuShortcutKeyMask
      )
      panel.getInputMap.put(closeKey, "closeWindow")
      panel.getActionMap.put(
        "closeWindow",
        new AbstractAction("Close Window") {
          override def actionPerformed(e: ActionEvent) {
            frame.setVisible(false)
            frame.dispose()
          }
        }
      )
      frame.pack()
      frame.setVisible(true)
    }
  }

  def logSections(sections: Seq[DocumentSection], pageNums: Option[Seq[Int]]): Unit = {
    if (logSections) {
      val text = sections.flatMap(_.paragraphs).groupBy(_.page)
      val titles = sections.flatMap(_.title).groupBy(_.page)
      val pages = if (pageNums.isDefined) pageNums.get else text.keySet ++ titles.keySet
      pages.foreach { pageNum =>
        val pageText = text.getOrElse(pageNum, Seq())
        val pageTitles = titles.getOrElse(pageNum, Seq())
        val annotations = Seq(
          Annotations(pageText.map(_.region), VisualLogger.ParagraphColor),
          Annotations(pageTitles.map(_.region), VisualLogger.SectionColor)
        )
        addToLog(pageNum, SectionsKey, annotations)
      }
    }
  }

  def logGraphicCluster(pageNum: Int, raw: Seq[Box], clustered: Seq[Box]): Unit = {
    if (logGraphicClustering) {
      val graphicAnnotation = Annotations(raw, VisualLogger.GraphicalElementColor, dashed = true)
      val clusteredAnnotation = Annotations(
        clustered,
        VisualLogger.GraphicClusterColor,
        4
      )
      val annotations = Seq(graphicAnnotation, clusteredAnnotation)
      addToLog(pageNum, GraphicsClusterKey, annotations)
    }
  }

  def logExtractions(page: PageWithGraphics): Unit = {
    if (logExtraction) {
      val graphicAnnotation = Annotations(page.graphics, VisualLogger.GraphicalElementColor)
      val paragraphAnnotation = Annotations(
        page.paragraphs.map(_.boundary),
        VisualLogger.ParagraphColor,
        2
      )
      val lineAnnotation = Annotations(
        page.paragraphs.flatMap(_.lines.map(_.boundary)),
        VisualLogger.TextLineColor
      )
      val nonFigureAnnotation = Annotations(
        page.classifiedText.allText.map(_.boundary) ++
          page.nonFigureGraphics,
        VisualLogger.NonFigureColor
      )
      val annotations =
        Seq(graphicAnnotation, paragraphAnnotation, lineAnnotation, nonFigureAnnotation)
      addToLog(page.pageNumber, TextAndGraphicsExtractionKey, annotations)
    }
  }

  def logFigures(page: Int, figures: Seq[Figure]): Unit = {
    if (logFigures) {
      val regionBB = Annotations(figures.map(_.regionBoundary), Color.GREEN, 2)
      val captionBB = Annotations(figures.map(_.captionBoundary), Color.BLUE, 2)
      val joint = Annotations(
        regionBB.boxes.zip(captionBB.boxes).map {
          case (region, capt) =>
            val c = Box.container(Seq(region, capt))
            Box(c.x1 - 4, c.y1 - 4, c.x2 + 4, c.y2 + 4)
        },
        VisualLogger.JointCaptionFigureColor
      )
      val annotations = Seq(regionBB, captionBB, joint)
      addToLog(page, FiguresKey, annotations)
    }
  }

  def logRasterizedFigures(page: Int, figures: Seq[RasterizedFigure]): Unit = {
    if (logCleanFigures) {
      val regionBB = Annotations(
        figures.map(_.figure.regionBoundary),
        VisualLogger.FigureRegionColor
      )
      val cleanedBB = Annotations(
        figures.map(f => f.imageRegion.scale(72.0 / f.dpi)),
        VisualLogger.CleanedFigureColor,
        dashed = true
      )
      addToLog(page, CleanedFiguresKey, Seq(regionBB, cleanedBB))
    }
  }

  def logPagesWithCaption(pageWithCaptions: PageWithCaptions): Unit = {
    if (logCaptions) {
      val allAnnotations = Seq(
        Annotations(pageWithCaptions.captions.map(_.boundary), VisualLogger.CaptionColor),
        Annotations(pageWithCaptions.graphics, VisualLogger.GraphicalElementColor),
        Annotations(pageWithCaptions.paragraphs.map(_.boundary), VisualLogger.ParagraphColor)
      )
      addToLog(pageWithCaptions.pageNumber, CaptionLocationKey, allAnnotations)
    }
  }

  def logRegions(regions: PageWithBodyText): Unit = {
    if (logRegions) {
      val allAnnotations = Seq(
        Annotations(
          regions.nonFigureGraphics ++ regions.classifiedText.allText.map(_.boundary) ++
            regions.bodyText.map(_.boundary),
          VisualLogger.NonFigureColor,
          3,
          true
        ),
        Annotations(regions.otherText.map(_.boundary), VisualLogger.OtherTextColor, 1),
        Annotations(regions.captions.map(_.boundary), VisualLogger.CaptionColor, 1, true),
        Annotations(regions.graphics, VisualLogger.GraphicalElementColor)
      )
      addToLog(regions.pageNumber, RegionClassificationKey, allAnnotations)
    }
  }

  /** Show `annotations` for the given page/layer name, allows multiple annotations to be registered
    * for the same name.
    */
  def logGroup(page: Int, groupName: String, annotations: Seq[Annotations]): Unit = {
    require(!ReservedKeys.contains(groupName), s"$groupName is a reserved annotation layer name")
    var i = 0
    while (logs.contains(groupName + i.toString) && logs(groupName + i.toString).contains(page)) {
      i += 1
    }
    addToLog(page, groupName + i.toString, annotations)
  }

  /** Show `annotations` for annotation layer `name` and page `page` */
  def log(page: Int, name: String, annotations: Seq[Annotations]): Unit = {
    require(!ReservedKeys.contains(name), s"$name is a reserved annotation layer name")
    addToLog(page, name, annotations)
  }

  private def addToLog(page: Int, name: String, annotations: Seq[Annotations]): Unit = {
    val log = logs.getOrElse(name, Map[Int, Seq[Annotations]]())
    require(!log.contains(page), s"Annotations have already been set for $name page: $page")
    logs = logs + (name -> (log + (page -> annotations)))
  }
}
