Figure Extraction Evaluation
======

This is a set of python3 scripts for evaluating and comparing systems
that locate the figures, tables, captions, and section titles within PDFs. It also includes
functionality for building datasets of gold standard annotations.

## Overview

## Data and Setup
There are two datasets, consisting of sets of PDFs and annotations for figures, tables and captions within
them. Additionally a subset of the documents have had their section titles manually extracted.
Annotations are included in the repo. To avoid copyright concerns, we only distribute URLs to the PDFs,
so to run an evaluation the papers will have to be downloaded first.
When grading extractors it is also helpful to ensure the extracted bounding boxes get cropped to the same
rasterization of the PDF as the gold annotations. For this purpose gray scale images of each page for
each PDF need to be built. Finally, for debugging, it can be nice to have colored images for each page
as well, so those images can also be generated.

The papers can be downloaded with

`python download_from_urls.py -g`

This downside of using URLs is that they can become out of date / die. We have made an effort to use URLs from stables sources,
but if some PDFs fail to download raise an issue and I will see if I can update the links or provide the PDF in another way.
Generating the related rasterized images requires the poppler-utility "pdftoppm" to be installed.

### Figure Extraction Evaluation
Extractors, which are programs that can extract figure/caption bounding regions from PDFs.
Extractors are listed in extractors.py.

To evaluate a dataset/extractor pair there are the following four scripts:

"build_evaluation.py" takes as input the name of a dataset and extractor, and scores the given
extractor against the given dataset. The result can be saved to to disk in a pickled file.

"parse_evaluation.py" reads a pickled evaluation file and prints the evaluation results, it can also
provide visualization of the ground truth compared to the extractor's output.

"compare_evaluation.py" takes as input two pickled evaluations and prints the PDFs and Figures
for which the two evaluations differed.

"time_extractor.py" which measures the time an extractor takes to process a corpus without
evaluating the results.

Existing evaluations to compare against exist in the "evaluations" folder.

### Section Title Extraction Evaluation
The script "build_section_eval.py" can be used to build section title evaluations. Programs that
can extract section titles are listed in `section_extractors.py`

## Dependencies:
python3 and the python library 'Pillow'.

If the PDFs are being downloaded from URLs the 'requests' library is also needed,
as well as the poppler utility 'pdftoppm' to rasterize the PDF pages

Building new datasets requires some additional dependencies, see datasets/README.md

## Workflow
A typical workflow using this setup might be (after downloading everything):

1. Makes some changes to an extractor
2. Run "build_evaluation.py" to re-evaluate the extractor. For example:

`python build_evaluation.py conference pdffigures2 -o new_evaluation.pkl`

to evaluate the extractor named "pdffigures2" against all the PDFs in the dataset named "conference"
 and save the results to "evaluation.pkl".

3. Run "compare_evaluation.py" to see what changed between this run and a previous run:

`python compare_evaluation.py new_evaluation.pkl old_evaluation.pkl`

to view how the results in "new_evaluation.pkl" differed from "old_evaluation.pkl"

3. Run "parse_evaluation.py" to review the scores or to visualize the errors:

`python parse_evaluation.py new_evaluation.pkl`

## Evaluation Methology
We evaluate figures and captions as follows:

Extractors are expected to return a figure region, a caption region, the page of the
figure, and an identifier or name of the figure (ex. For "Figure 1"), and optionally the caption text.

For each extraction, we consider the extraction correct if:

1. The page was the same as the ground truth page
2. The identifier was the same as the ground truth identifier
3. The returned region bounding has (area of overlap)/(area of union) score of >= 0.8 with the
ground truth (same scoring criterion as used in PASCAL, http://host.robots.ox.ac.uk/pascal/VOC/)
4. The caption bounding box is scored in the same way, although we additionally
consider the caption correct if its text matches the ground truth text.

Returned figures with page numbers and identifiers that are not contained in the gold standard
are considered FPs, figures in the gold standard with page numbers and identifiers that are not found in
the extracted figures are considered FNs.
