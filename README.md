# PDFFigures 2.0
PDFFigures 2.0 is a Scala based project built to extract figures, captions, tables and section titles from
scholarly documents, with a strong focus on documents from the domain of computer science.
See our [paper](http://ai2-website.s3.amazonaws.com/publications/pdf2.0.pdf) for more details.

## Input and Output
PDFFigures 2.0 takes as input a scholarly document in PDF form. Its output will be a list of
'Figure' objects where, for each figure, we have identified:

1. The page the figure occurs in (0 based).
2. The bounding box of the figure within that page, given as pixel coordinates where
(0,0) is the top left of the PDF's cropbox and the page is assumed to be rendered at 72 DPI.
3. Any text that occurs inside the figure.
4. The caption of the figure.
5. The bounding box of the caption.
6. The 'name' of the figure as deduced from the caption. Usually, this is a number (ex. the name
of a figure captioned "Figure 1" would be "1"), but it might take on some other form
depending on the PDF parsed.
7. Whether the figure was labelled as a Table or a Figure, again based on the caption.

PDFFigures 2 also supports the ability to save images of the extracted figures as rasterized images.
Currently, we support any format that a BufferedImage can be saved to (png, jpeg, etc.). More
experimentally, if pdftocairo is installed it can be used to save the figures to
a selection of vector graphics formats (svg, ps, eps, etc.).

PDFFigures 2 only seeks to extract figures or tables that have been captioned, in which case
we define a figure to be all elements on the page that the caption refers to. If a figure has
subfigures, the returned figure will include all the subfigures. If a table or figure includes text
titles or comments, those elements will be included in the figure.

### Installation
Clone the repo and then run with sbt.

For licensing reasons, PDFFigures2 does not include libraries for some image formats. Without these
libraries, PDFFigures2 cannot process PDFs that contain images in these formats. If you have no
licensing restrictions in your project, we recommend you add these additional dependencies to your
project as well:
```
  "com.github.jai-imageio" % "jai-imageio-core" % "1.2.1",
  "com.github.jai-imageio" % "jai-imageio-jpeg2000" % "1.3.0", // For handling jpeg2000 images
  "com.levigo.jbig2" % "levigo-jbig2-imageio" % "1.6.5", // For handling jbig2 images
```

### Command Line Tools
PDFFigures 2 provides two CLI tools. One, 'FigureExtractorBatchCli', can be used to extract figures
from a large number of PDFs and save the results to disk. The second, 'FigureExtractorVisualizationCli',
works on a single PDF and provides extensive debug visualizations. Note it is recommended to use the "-Dsun.java2d.cmm=sun.java2d.cmm.kcms.KcmsServiceProvider" to get the best performance out of the PDF parser, see here[https://pdfbox.apache.org/2.0/getting-started.html]

To run on a PDF and get a preview of the results use:

`sbt "runMain org.allenai.pdffigures2.FigureExtractorVisualizationCli /path/to/pdf"`

To get a visualization of how the PDF was parsed:

`sbt "runMain
org.allenai.pdffigures2.FigureExtractorVisualizationCli /path/to/pdf" -r`

To get a visualization of all the intermediate steps:

`sbt "runMain org.allenai.pdffigures2.FigureExtractorVisualizationCli /path/to/pdf" -s`

To run on lots of PDFs while saving the images, figure objects, and run statistics:

`sbt "runMain
org.allenai.pdffigures2.FigureExtractorBatchCli /path/to/pdf_directory/
-s stat_file.json -m /figure/image/output/prefix -d /figure/data/output/prefix"`

To compile a stand-alone JAR with these tools:

`sbt assembly`

### Section Titles
FigureExtractor has experimental support for additionally identifying section titles. Section
titles, along with the PDF's text, can be returned from the BatchCli using the "-g" flag.
The output will the full text of the PDF, organized into sections.
An effort is made to identify the abstract, if there is one, and to exclude
text like page headers, authors names, and page numbers.
Text inside figures and captions will also be excluded from the main
text and encoded separately.
Note that while the extracted section titles have been found to be reliable, the 
quality of the returned text
itself has not been tested and is mostly what is returned by PDFBox's `ExtractText`

### Interface
FigureExtractor exports its high level programmatic interfaces in FigureExtractor.scala

### Multithreading
FigureExtractor rigorously checks Thread.interrupted and so can be timed out easily.
FigureExtractorBatchCli supports multi-threading.

## Implementation Overview
See the paper for more details. In brief, the input PDF is pushed through the following steps:

1. Text is extracted from the PDF. See TextExtractor.scala.
2. Page numbers, page headers, and abstracts are identified and removed. See
FormattingTextExtractor.scala.
3. Some statistics are gathered about the remaining text. We use these
statistics later to identify text that is atypical/unusual since that text is likely to be part
of a figure. See DocumentLayout.scala.
4. The locations of captions within the text are identified. See CaptionDetector.scala.
5. For each page with captions, we identify where any graphical/non-textual elements are. See
GraphicExtractor.scala.
6. We determine the entirety of each caption. (Previous steps just identified the line that
started each caption; this step identifies the full text of each caption.) See CaptionBuilder.scala.
7. The text in each page that contained a caption is classified as "BodyText" or "Other." Future
 steps will assume "BodyText" is never part of a Figure/Table but "Other" text might be. See
 RegionClassifier.scala.
8. Figures are located using the classification from the previous step. See
FigureDetector.scala. This has two substeps:
  * For each caption, a number of regions within the page are "proposed" as possible figure
regions. We propose regions that are adjacent to the caption and contain only "Other" text and
graphical elements.
  * A scoring function is used to select the best proposal to match to each caption. This process
   also makes sure we don't select overlapping figure regions for two captions.
9. Finally, the figures are optionally rendered to images using PDFBox. See FigureRenderer.scala.
10. More experimentally, section titles can be also extracted. See SectionTitleExtractor.scala.
11. Then, the document can be broken up to logical sections. See SectionedTextBuilder.scala.

## Evaluation
This repo includes python-based scripts to evaluate figure extractors and two
datasets with ground truth labels. See the evaluation directory.

## Common Sources of Errors
FigureExtractor has been tested on papers selected from [Semantic Scholar](www.semanticscholar.com).
It is not well tested on domains outside of computer science. 
When errors do occur, some common causes are:

1. Poorly Encoded PDFs: Some PDFs can appear to be perfectly fine in a PDF viewer, but when we try
to extract the text we might get garbage, or we might get a bunch of extraneous text that is not
visible to the eye. Trying to ignore text that is encoded in the PDF as being the background
color (might?) be a good to start to solving these issues, but is not implemented.
2. Text Classification: Text classification works well for tables and most figures, but we get
some errors for text-heavy figures (such as a figure outlining the steps in an algorithm).
RegionClassifier.scala will sometimes classify bullet points and equations as
non-body text, which can cause those text elements to get incorrectly chunked into figures.
3. Region Proposing: Even when text classification is accurate, generating good proposed figure
regions can be a non-trivial task. It is in particular important to build proposals that do not
encompass multiple figures, which is sometimes quite difficult if there are many figures on a
single page.
4. Caption Building: For some cases where captions are very closely packed to the following text,
our returned captions will include too much text. For some some papers with unusual
caption formats, we might fail to include some text in the captions.


## Unhandled Edge Cases
There are a few edge cases were we consistently fail, due to hard coded assumptions or special
cases we do not handle at the moment:

1. "L" shaped figures. For example, see 
evaluation/datasets/s2/pdfs/202042e6f88abe690a55e136475053a3eac68d40.pdf, page 7. To handle these
one would need to adjust the API to allow figure regions to be described by multiple bounding
boxes and then adjust "FigureDetector.scala" to return them.
2. Three adjacent figures, where the figures share borders with each other. For example, see 
evaluation/datasets/conference/pdfs/icml10_4.pdf. I think this would not be too difficult to
handle as a special case. It would require heuristically guessing how to split up
the region all the figures occupy.
3. Rotated text. For example, see evaluation/datasets/conference/pdfs/W10-1721.pdf page 6.
Unfortunately PDFBox does not handle extracting rotated text; it tends to
group rotated text as paragraphs with each character being line. This means for pages
with captions rotated at a 90 degree angle we will be unable to detect any captions that exist on
that page and then be unable to extract the figures. Handling this might require post-processing the
text we get from PDFBox to attempt to get coherent lines of text for these cases.
4. Captions on the same line. For example, see evaluation/datasets/conference/pdfs/icml14_9.pdf, page 5.
PDFBox will group both captions into the same line. Our caption detection code assumes that
captions always start lines (this assumption is almost never wrong outside
of these cases), which causes us to miss the second caption. This might be relatively
easy to address by checking each line we find to have a caption for large gap between the words
in the line, followed by a second caption.
5. Figures above the abstract. Currently it is assumed all text above the abstract 
is not part of a figure. This helps avoid false positives induced by including emails/names/title 
from the header in figures on the first page, but there is probably a way to
relax this assumption to resolve this issue.


## Contact
Christopher Clark, chrisc@allenai.org