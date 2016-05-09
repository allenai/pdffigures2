from datasets import datasets
import extractors
import sys
from pdffigures_utils import *
import argparse
from os.path import isfile
import pickle
from time import time, strftime
from parse_evaluation import print_pr
from multiprocessing import Pool

"""
Script for evaluating an extractor against a dataset
"""

""" How high the intersection/union ration of two bounding boxes has to be
for them to be considered correct """
UNION_INTERSECT_OVERLAP_THRESH = 0.8


def pair_extractions(labels, extractions):
    """
    :param labels: list of 'Label' figures, currently we assume they are uniquely identified
        by (page, figure_type, and number)
    :param extractions: list of 'extraction' Figures
    :return: yields (true_figure, extracted_figure) for each name found in either
             labels of extractions, either true_figure or extracted_figure may be None
             if there were figures in `extractions` that were not found in `labels` or vice versa
    """

    label_ids_to_fig = {fig.get_id(): fig for fig in labels}
    if len(label_ids_to_fig) != len(labels):
        raise ValueError("Multiple labels with the same ID")

    ids_in_extractions = set(fig.get_id() for fig in extractions)
    for fig_id, fig in label_ids_to_fig.items():
        if fig_id not in ids_in_extractions:
            yield fig, None

    for fig in extractions:
        fig_id = fig.get_id()
        yield label_ids_to_fig.get(fig_id), fig


def grade_document_extractions(document, extractions, compare_caption_text, crop_extractions):
    """
    Evaluates an extraction

    :param document: data/dataset.Document containing the true figures
    :param extractions: list of utils.Figure an extractor returned
    :param compare_caption_text: whether to grade captions by comparing the caption text as well as comparing
        the bounding boxes of the captions
    :param crop_extractions: whether to whitepsace crop the extractions
    :return: List of utils.EvaluatedFigure
    """
    evaluated_figures = []
    pages = document.pages_annotated
    true_figures = document.figures
    extracted_figures = []
    for ex in extractions:
        if ex.page in pages:
            extracted_figures.append(ex)

    for true_figure, extracted_figure in pair_extractions(true_figures, extracted_figures):
        grayscale_images = document.gray_images
        if crop_extractions and grayscale_images is None:
            raise ValueError("Unable to corp extraction since grayscale image have not been built" +
                             " for document %s" % document.doc_id)
        if extracted_figure is None:
            if true_figure is None:
                raise RuntimeError()
            error = Error.missing
        elif extracted_figure.caption_bb is None:
            error = Error.no_caption_found
        elif true_figure is None:
            if extracted_figure.region_bb is None:
                error = Error.false_positive_no_region
            else:
                error = Error.false_positive
        else:
            # At this point we have a valid `true_figure` and an `extracted_figure` with a caption_bb
            page = true_figure.page
            if crop_extractions:
                gray_img = Image.open(grayscale_images[page])
                bw_img = gray_img.convert('L').point(lambda x: 0 if x<200 else 255, '1')
                extracted_capt_box, extracted_region_box = \
                    scale_and_crop_figure(extracted_figure, bw_img, document.dpi)
            else:
                extracted_capt_box, extracted_region_box = scale_figure(extracted_figure, document.dpi)
            true_capt_box, true_region_box = scale_figure(true_figure, document.dpi)
            caption_correct = box_overlap(extracted_capt_box, true_capt_box)[0] >= UNION_INTERSECT_OVERLAP_THRESH
            if not caption_correct and compare_caption_text:
                caption_correct = compare_captions(true_figure.caption, extracted_figure.caption)
            if extracted_region_box is None:
                if caption_correct:
                    error = Error.right_caption_no_region
                else:
                    error = Error.wrong_caption_no_region
            else:
                region_correct = box_overlap(extracted_region_box, true_region_box)[0] >= UNION_INTERSECT_OVERLAP_THRESH
                if not region_correct and not caption_correct:
                    error = Error.wrong_caption_and_region
                elif not region_correct:
                    error = Error.wrong_region_box
                elif not caption_correct:
                    error = Error.wrong_caption_box
                else:
                    error = Error.correct

        evaluated_figures.append(EvaluatedFigure(true_figure, extracted_figure, error, document.doc_id))

    num_missing = sum(1 for x in evaluated_figures if x.error == Error.missing)

    # Sanity check, one error per output minus the missing examples
    if len(extracted_figures) != (len(evaluated_figures) - num_missing):
        raise ValueError("Have %d extractions %d errors - %d missing = %d recorded" % (len(extracted_figures),
            len(evaluated_figures), num_missing, len(evaluated_figures) - num_missing))
    return evaluated_figures


def evaluate(dataset, extractor, doc_ids_to_use,
             compare_caption_text, crop_extractions, verbose):
    all_errors = []
    documents = dataset.load_doc_ids(doc_ids_to_use)
    extractor.start_batch([x.pdffile for x in documents])
    for i, doc in enumerate(documents):
        if verbose:
            print("checking PDF %s (%d of %d)" % (doc.doc_id, i + 1, len(documents)))
        extractions = extractor.get_extractions(doc.pdffile, dataset.NAME, doc.doc_id)
        errors = grade_document_extractions(doc, extractions, compare_caption_text, crop_extractions)
        all_errors += errors

    return all_errors


def main():
    parser = argparse.ArgumentParser(description='Evaluate a figure extractor')
    parser.add_argument("dataset", choices=list(datasets.DATASETS.keys()), help="Name of the dataset to evaluate on")
    parser.add_argument("extractor", choices=list(extractors.EXTRACTORS.keys()), help="Name of the extractor to test")
    parser.add_argument("-c", "--dont-crop-extractions", action='store_true', help="Don't crop the extractions " +
        "produced by the extractor to the same grayscale the annotations were cropped to")
    parser.add_argument("-b", "--dont-compare-caption-text", action='store_true',
                        help="Evaluate caption text by only comparing the caption bounding boxes, not the caption text")
    parser.add_argument("-q", "--quiet", action='store_true', help="Reduce printed output")
    parser.add_argument("-p", "--processes", type=int, default=1, help="Number of processes to use, defaults to one")
    parser.add_argument("-d", "--docs", nargs="+", help="Which document ids to evaluate on, can't be used in conjunction with `which`")
    parser.add_argument("-o", "--output", nargs="?", const=True, help="Where to store the output, " +
        "if this -o flag is used without parameters a default filename is chosen based on the current date.")
    parser.add_argument("-r", "--compare-non-standard", action='store_true', help="Don't skip PDF in the dataset that" +
                                                                                  "are marked as being non-standard")
    args = parser.parse_args()

    dataset = datasets.get_dataset(args.dataset)
    verbose = not args.quiet

    # Set `output_file` to None or the filename to write the output to
    if args.output is not None:
        if args.output == True:
            time_str = strftime("%m-%d-%H-%M")
            output_file = "%s-%s_%s.pkl" % (args.dataset, args.extractor, time_str)
            print("Using output file %s" % output_file)
        else:
            output_file = args.output
        if isfile(args.output):
            raise ValueError("File %s already exists" % args.output)
    else:
        output_file = None

    # set `doc_ids_to_use` to the document ids to evaluate on
    if args.docs is not None:
        which = args.docs
        doc_ids_to_use = []
        for doc_id in dataset.get_doc_ids():
            if doc_id in which:
                doc_ids_to_use.append(doc_id)
        if len(set(which) - set(doc_ids_to_use)) > 0:
            raise ValueError("Could not find doc ids %s" % str(set(which) - set(doc_ids_to_use)))
        if verbose:
            print("Using %d user supplied documents" % len(doc_ids_to_use))
    else:
        doc_ids_to_use = dataset.get_doc_ids()

    compare_caption_text = not args.dont_compare_caption_text
    crop = not args.dont_crop_extractions

    # Remove non-standard documents from `doc_ids_to_use`
    if not args.compare_non_standard:
        nonstandard_docs = dataset.get_nonstandard_doc_ids()
        nonstandard_docs = nonstandard_docs.intersection(doc_ids_to_use)
        doc_ids_to_use = list(set(doc_ids_to_use) - nonstandard_docs)

    # Load the extractor to use and set `evaluation` to the completed evaluation
    extractor = extractors.get_extractor(args.extractor)
    print("Evaluating %s (%s)" % (sys.argv[2], extractor.get_version()))
    if args.processes == 1:
        evaluated_figures = evaluate(dataset, extractor, doc_ids_to_use, compare_caption_text, crop, verbose)
    else:
        pool = Pool(args.processes)
        num_docs = len(doc_ids_to_use)
        chunk_size = num_docs // args.processes + 1
        chunks = [doc_ids_to_use[i:i + chunk_size] for i in
                  range(0, num_docs, chunk_size)]
        chunks_with_args = [(dataset, extractors.get_extractor(args.extractor),
                             x, compare_caption_text, crop, verbose) for x in chunks]
        evaluated_figures = []
        for evaluated_figures_in_chunk in pool.starmap(evaluate, chunks_with_args):
                evaluated_figures += evaluated_figures_in_chunk

    evaluation = Evaluation(dataset.NAME, dataset.get_version(),
                            extractor.NAME, extractor.get_version(),
                            extractor.get_config(), evaluated_figures,
                            compare_caption_text, doc_ids_to_use, time())

    # Save the resulting evaluation
    if output_file is not None:
        with open(output_file, "wb") as f:
            pickle.dump(evaluation, f)
            print("Evaluation saved to %s" % output_file)

    print_pr(evaluation, False)

if __name__ == "__main__":
    main()
