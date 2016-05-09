from datasets import datasets
import extractors
import argparse
from time import time


def main():
    parser = argparse.ArgumentParser(description='Time a figure extractor')
    parser.add_argument("dataset", choices=list(datasets.DATASETS.keys()), help="Name of the dataset to evaluate on")
    parser.add_argument("extractor", choices=list(extractors.EXTRACTORS.keys()), help="Name of the extractor to test")
    parser.add_argument("-w", "--write-figures", action="store_true")
    parser.add_argument("-r", "--compare-non-standard", action='store_true', help="Don't skip PDF in the dataset that" +
                                                                                  "are marked as being non-standard")
    parser.add_argument("-q", "--quiet", action='store_true', help="Reduce printed output")
    args = parser.parse_args()

    verbose = not args.quiet
    dataset = datasets.get_dataset(args.dataset)
    doc_ids_to_use = dataset.get_doc_ids()

    if not args.compare_non_standard:
        nonstandard_docs = dataset.get_nonstandard_doc_ids()
        nonstandard_docs = nonstandard_docs.intersection(doc_ids_to_use)
        print("Skipping %d non-standard docs" % len(nonstandard_docs))
        doc_ids_to_use = list(set(doc_ids_to_use) - nonstandard_docs)

    file_map = dataset.get_pdf_file_map()
    filenames = [file_map[x] for x in doc_ids_to_use]
    extractor = extractors.get_extractor(args.extractor)
    print("Starting time extractor %s dataset %s" % (args.extractor, args.dataset))
    t0 = time()
    extractor.time(filenames, args.write_figures, verbose=verbose)
    print(time() - t0)

if __name__ == "__main__":
    main()
