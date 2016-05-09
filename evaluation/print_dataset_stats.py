import argparse
from datasets import datasets
from pdffigures_utils import FigureType

"""
Crude script to print some basic statistics for a given dataset
"""

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("dataset",
                        choices=list(datasets.DATASETS.keys()))
    args = parser.parse_args()
    dataset = datasets.get_dataset(args.dataset)
    docs = dataset.load_docs("all")
    total = 0
    for doc in sorted(docs, key=lambda x: x.doc_id):
        figures = doc.figures
        num_figures = len([x for x in figures if x.figure_type == FigureType.figure])
        num_tables = len([x for x in figures if x.figure_type == FigureType.table])
        print("%s: figures=%d, tables=%d, total=%d" % (doc.doc_id, num_figures, num_tables, num_figures + num_tables))
        total += num_tables + num_figures
        if doc.non_standard:
            print("Doc %s marked as being non-standard" % doc.doc_id)
    print("%d total figures" % total)


if __name__ == "__main__":
    main()
