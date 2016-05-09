import argparse
from os.path import dirname, realpath, join
from unicodedata import normalize
import section_extractors
import json

from datasets import datasets

"""
Script to evaluate programs the can extract section titles (from "section_extractors.py")
using the annotations stored in "datasets/section-annotations.json"
"""

_BASE_DIR = dirname(realpath(__file__))
_ANNOTATIONS_FILE = join(_BASE_DIR, "datasets", "section-annotations.json")


""" Always exclude these section names  """
EXCLUDE_NAMES = ["abstract"]


class SectionName(object):

    def __init__(self, raw_name):
        self.raw_name = raw_name
        self.cannonical_name = normalize('NFKC', raw_name.replace(" ", "").replace(".", "").replace("-", ""))

    def __hash__(self):
        return hash(self.cannonical_name)

    def __eq__(self, other):
        if isinstance(other, SectionName):
            return self.cannonical_name == other.cannonical_name
        else:
            return False

    def __repr__(self):
        return "Section(%s)" % self.raw_name

    def __str__(self):
        return self.raw_name


class EvaluatedDocument(object):

    def __init__(self, doc_id, url, correct, false_negatives, false_positives):
        self.doc_id = doc_id
        self.url = url
        self.correct = correct
        self.false_negatives = false_negatives
        self.false_positives = false_positives

    @property
    def true_figures(self):
        return self.correct + self.false_negatives

    @property
    def extracted_figures(self):
        return self.false_positives + self.correct


class AnnotatedDocument(object):

    def __init__(self, filepath, doc_id, url, sections):
        self.filepath = filepath
        self.doc_id = doc_id
        self.url = url
        self.sections = sections


# This is rather hacky, we assume that doc_ids are unique across datasets
def get_doc_ids_to_url():
    """ Returns map of doc_id -> url for all datasets """
    url_map = {}
    for dataset in datasets.DATASETS.values():
        dataset = dataset()
        for k,v in dataset.get_urls().items():
            if k in url_map:
                raise ValueError()
            url_map[k] = v
    return url_map


def get_doc_ids_to_file():
    """ Returns map of doc_id -> url for all datasets """
    pdf_map = {}
    for dataset in datasets.DATASETS.values():
        dataset = dataset()
        for k,v in dataset.get_pdf_file_map().items():
            if k in pdf_map:
                raise ValueError()
            pdf_map[k] = v
    return pdf_map


def load_annotations():
    """ Returns map of doc_id -> AnnotatedDocument """
    with open(_ANNOTATIONS_FILE) as f:
        raw_annotations = json.load(f)

    all_docs = set(raw_annotations.keys())
    url_map = get_doc_ids_to_url()
    file_map = get_doc_ids_to_file()
    annotations = {}
    for doc_id in all_docs:
        if doc_id in raw_annotations:
            sections = [SectionName(x) for x in raw_annotations[doc_id]]
        else:
            sections = None
        url = url_map[doc_id]
        filename = file_map[doc_id]
        annotations[doc_id] = AnnotatedDocument(filename, doc_id, url, sections)

    return annotations


def grade_extraction(annotated_doc, extracted_sections):
    true_sections = annotated_doc.sections
    filtered_true_sections = [x for x in true_sections if not any(e in x.raw_name.lower() for e in EXCLUDE_NAMES)]
    filtered_extracted_sections = [x for x in extracted_sections if not any(e in x.raw_name.lower() for e in EXCLUDE_NAMES)]

    num_extracted_sections = len(filtered_extracted_sections)
    num_true_sections = len(filtered_true_sections)
    false_negative = []
    correct = []
    for section in filtered_true_sections:
        # We remove the sections as we go so if the same name appears twice it
        # wouldn't get counted as a TP, and so it
        if section in filtered_extracted_sections:
            correct.append(section)
            filtered_extracted_sections.remove(section)
        else:
            false_negative.append(section)

    false_positives = filtered_extracted_sections
    if not len(correct) + len(false_negative) == num_true_sections:
        raise ValueError()
    if not len(correct) + len(false_positives) == num_extracted_sections:
        raise ValueError()

    return EvaluatedDocument(annotated_doc.doc_id, annotated_doc.url,
                             correct, false_negative, false_positives)


def print_pr(evaluated_docs):
    total_true_figures = 0
    total_extracted_figures = 0
    total_correct = 0
    fps = 0
    fns = 0
    for evaluated_doc in evaluated_docs:
        fps += len(evaluated_doc.false_positives)
        fns += len(evaluated_doc.false_negatives)
        total_true_figures += len(evaluated_doc.true_figures)
        total_correct += len(evaluated_doc.correct)
        total_extracted_figures += len(evaluated_doc.extracted_figures)
    r = total_correct / total_true_figures
    p = total_correct / total_extracted_figures
    if total_correct == 0:
        f1 = 0
    else:
        f1 = 2*p*r / (p + r)
    print("Correct: %d" % total_correct)
    print("FPs: %d" % fps)
    print("FNs: %d" % fns)
    print("PRECISION: %0.3f" % p)
    print("RECALL: %0.3f" % r)
    print("F1: %0.3f" % f1)


def list_errors(evaluated_docs, only_errors):
    for doc in sorted(evaluated_docs, key=lambda x: ("-" in x.doc_id, x.doc_id)):
        print("\n" + "*" * 10 + " Paper: " + doc.doc_id + " " + "*" * 10)
        print("Url: %s" % doc.url)
        all_correct = len(doc.false_positives) + len(doc.false_negatives) == 0
        if all_correct and only_errors:
            print("Everything correct (%d titles)" % len(doc.correct))
            continue
        print("Correct:")
        print("\n".join("\t" + str(x) for x in doc.correct))
        print("False Positives:")
        print("\n".join("\t" + str(x) for x in doc.false_positives))
        print("False Negatives:")
        print("\n".join("\t" + str(x) for x in doc.false_negatives))


def main():
    parser = argparse.ArgumentParser(description='Evaluate a figure extractor')
    parser.add_argument("extractor", choices=list(section_extractors.EXTRACTORS.keys()), help="Extractor to test")
    parser.add_argument("-l", "--list_errors", nargs="?", const="all", choices=["all", "errors"],
                        help="List details of the evaluations, if `errors` don't show results for 100% correct PDFs")
    parser.add_argument("-d", "--doc_id", help="Only test on the given documents")
    args = parser.parse_args()

    annotations = load_annotations()
    print("Have %d labelled documents" % len(annotations))
    true_sections = {k:v for k,v in load_annotations().items() if v.sections is not None}
    if args.doc_id:
        true_sections = {args.doc_id: true_sections[args.doc_id]}

    extractor = section_extractors.get_extractor(args.extractor)
    extracted_sections = extractor.get_sections([x.filepath for x in true_sections.values()])
    extracted_sections = {k:[SectionName(x) for x in v] for k,v in extracted_sections.items()}

    evaluated_docs = []
    for doc_id, annotated_doc in true_sections.items():
        evaluated_docs.append(grade_extraction(annotated_doc, extracted_sections[doc_id]))
    print_pr(evaluated_docs)

    if args.list_errors:
        list_errors(evaluated_docs, args.list_errors == "errors")

if __name__ == "__main__":
    main()
