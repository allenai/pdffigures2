package org.allenai.pdffigures2

import org.allenai.common.testkit.UnitSpec
import org.apache.pdfbox.pdmodel.PDDocument

/** These tests verify that figure extraction filters are successfully catching and removing bad
  * extractions.
  */
class TestExtractionFilters extends UnitSpec {
  val allowOcr = false
  val detectSectionTitlesFirst = false
  val rebuildParagraphs = true
  val dpi = 200

  val extractor = new FigureExtractor(
    allowOcr = allowOcr,
    detectSectionTitlesFirst = detectSectionTitlesFirst,
    rebuildParagraphs = rebuildParagraphs,
    ignoreWhiteGraphics = true,
    cleanRasterizedFigureRegions = true
  )

  /** The figures in this paper, "Ephaptic coupling of cortical neurons", violate the system's
    * assumptions due to their side captions not spanning the entire height of the figure.
    * The extractor uses the upward proposal, which identifies the yellow header as the figure.
    * These extractions should be filtered out for being too close to the page boundary.
    */
  "Page boundary filter" should "filter out bad extractions" in {
    val pdf = PDDocument.load(
      getClass.getClassLoader.getResourceAsStream(
        "test-pdfs/f63cb20759fab2514802c3ef2a743c76bf9dc9f1.pdf"
      )
    )
    val figures = extractor.getFigures(pdf)
    assert(figures === Nil)
  }

  /** Figure 3 on page 6 of this paper, "Intercellular calcium waves in glia.",
    * violates the system's assumptions due to its two-column caption format.
    * The extractor uses the upward proposal, splitting the figure in half.
    * This extraction should be filtered out for splitting a figure.
    */
  "Graphics split filter" should "filter out bad extractions" in {
    val pdf = PDDocument.load(
      getClass.getClassLoader.getResourceAsStream(
        "test-pdfs/3a9202f9f176d3377516e3da0866cc19148c033b.pdf"
      )
    )
    val figures = extractor.getFigures(pdf, pages = Some(Seq(6)))
    assert(figures === Nil)
  }

  /** All figures should be extracted for this paper, "Open Information Extraction from the Web".
    * This ensures that when figures are empty, it's not because figure extraction is broken.
    */
  "Figures" should "all be extracted" in {
    val pdf = PDDocument.load(
      getClass.getClassLoader.getResourceAsStream(
        "test-pdfs/498bb0efad6ec15dd09d941fb309aa18d6df9f5f.pdf"
      )
    )
    val figures = extractor.getFigures(pdf).toList
    assert(figures.length === 2)
    assert(figures(0).figType === FigureType.Table)
    assert(figures(0).name === "1")
    assert(figures(0).page === 4)
    assert(
      figures(0).caption === "Table 1: Over a set of ten relations, TEXTRUNNER achieved a 33% lower error rate than KNOWITALL, while finding approximately as many correct extractions."
    )
    assert(figures(1).figType === FigureType.Figure)
    assert(figures(1).name === "1")
    assert(figures(1).page === 4)
    assert(
      figures(1).caption === "Figure 1: Overview of the tuples extracted from 9 million Web page corpus. 7.8 million well-formed tuples are found having probability ≥ 0.8. Of those, TEXTRUNNER finds 1 million concrete tuples with arguments grounded in particular real-world entities, 88.1% of which are correct, and 6.8 million tuples reflecting abstract assertions, 79.2% of which are correct."
    )
  }
}
