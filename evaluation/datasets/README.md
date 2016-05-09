Datasets
======

The only "export" of this module is datasets.py which contains hooks to load figure datasets.
If your only concern is using existing datasets that you can make use of "datasets.py" and
ignore everything else in this directory and the rest of this README.

## Format
A dataset is a collection of PDFs and their annotations specifying where the Figure and
Tables inside that exist. We also provide a cache for fully rasterized images of the pages of
each PDF, which we use to crop output or to display images to the users. Each PDF in a dataset is expected is
associated with an ID that is used to cross reference all this information. And finally, for some datasets
there are PDFs where only a subset of their pages were annotated, so datasets also need to record
which pages were/should be annotated for each PDF.

While any file format or storage method is fine as long as it conforms to the Dataset API, the way it has been
done so far is:

* Each dataset is its own directory
* Each directory has a 'pdfs' directory that stores all the PDF files as <document-id>.pdf
* Each directory has an annotations.json file that stores the annotations. These are stored as a dictionary with
document-id as keys, which map to dictionary of Figure with figure names as keys (see 'Figure' in pdffigures_utils.py)
alongid
* Each directory has a page_images_color and page_images_gray directory storing the color and
gray scale rasterized images in as <document-id>-page-<page#>.{jpg,pgm}
* Each directory optionally has a pages_annotated.json which indicates which pages of each PDF are/will be annotated for figures
* Each directory optionally has a non_standard_pdfs.txt file listing the document ids of PDFs that are non standard, optionally
followed by a space, followed by an arbitrary explanation of why the PDF is unusual. Mainly for OCRed PDFs
* Each dataset has an object in dataset.py which defines the dpi to use to render color and gray scale images,
the current dataset version, and if only a subsample of pages are going to be annotated,
MAX_PAGES_TO_ANNOTATE, PAGE_SAMPLE_PERCENT, PAGE_SAMPLE_SEED specify the maximum number pages to annotate per each
document, the percent of pages to annotated for each document, and the seed to use when sampling pages from each document


## Scripts
This directory includes a few additional scripts. Note parts of pipeline used to build these
datasets (in particular the annotation tool used) are not open sourced at the moment, so
the dataset generation process is not yet available. If there is interest in this let me know and
I can look into making it available.

* test_datasets.py contains some sanity checks on the existing datasets
* visualize_annotations.py can be used to build visualizations of the annotations in a dataset

