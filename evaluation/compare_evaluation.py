import argparse
import pickle
from collections import defaultdict

"""
Script for comparing two Evaluation objects
"""

def main():
    parser = argparse.ArgumentParser(description='Compare two evaluations')
    parser.add_argument("evaluation1")
    parser.add_argument("evaluation2")
    parser.add_argument("-d", "--documents", nargs="+", help="Only compare on these documents")
    args = parser.parse_args()

    with open(args.evaluation1, "rb") as f:
        eval1 = pickle.load(f)
    with open(args.evaluation2, "rb") as f:
        eval2 = pickle.load(f)

    if eval1.dataset_name != eval2.dataset_name:
        raise ValueError("Evaluations had different datasets (%s vs %s)" % (
                eval1.dataset_name, eval2.dataset_name))
    if eval1.dataset_version != eval2.dataset_version:
        print("WARNING: Evaluation compared different dataste versions (%s vs %s)" % (
                str(eval1.dataset_version), str(eval2.dataset_version)))
    if eval1.compare_caption_text != eval2.compare_caption_text:
        print("WARNING: One evaluation compared text and one did not")

    same_docs = set(eval1.docs).intersection(set(eval2.docs))
    print("Evaluations shared %d docs (%d in eval1, %d in eval2)" % (len(same_docs), len(eval1.docs), len(eval2.docs)))

    if args.documents is not None:
        if any(x not in same_docs for x in args.documents):
            raise ValueError()
        same_docs = set(args.documents)

    differences = 0
    # Build dictionary of figure_id -> all EvaluatedFigures with that id for evaluation1
    eval1_figs = defaultdict(list)
    for fig in eval1.evaluated_figures:
        if fig.doc in same_docs:
            eval1_figs[fig.get_id()].append(fig)

    # Build dictionary of figure_id -> all EvaluatedFigures with that id for evaluation2, also,
    # print any cases of a figure occurring in evalution2 but not evaluation1
    eval2_figs = defaultdict(list)
    for fig in eval2.evaluated_figures:
        if fig.doc in same_docs:
            eval2_figs[fig.get_id()].append(fig)
            if fig.get_id() not in eval1_figs:
                differences += 1
                print("Eval2 has %s: %s %s but Eval1 does not" % (fig.doc, fig.name, fig.error))

    for fig_id, figs in eval1_figs.items():
        doc, name = fig_id
        if fig_id not in eval2_figs:
            # Occurs in evaluation1 but not evaluation 2
            differences += 1
            for fig in figs:
                print("Eval1 has %s: %s %s but Eval2 does not" % (fig.doc, fig.name, fig.error))
        else:
            # fig_id occurs in both evaluations, check to see if they are of the same kind of error.
            # This is complicated by having to account for the possibility multiple EvaluatedFigures occur
            # for each fig_id
            other_figs = eval2_figs[fig_id]
            by_error = defaultdict(list)
            for fig in other_figs:
                by_error[fig.error].append(fig)
            no_matches = []
            for fig in figs:
                if len(by_error[fig.error]) > 0:
                    # Remove this kind of error since it occurs in both evaluations
                    by_error[fig.error].pop()
                else:
                    no_matches.append(fig)
            no_matches_other = []
            for v in by_error.values():
                no_matches_other += v
            if len(no_matches) == 1 and len(no_matches_other) == 1:
                differences += 1
                print("%s: %s E1=%s, E2=%s" % (
                    (doc, name, figs[0].error, other_figs[0].error)))
            elif len(no_matches_other) > 0 or len(no_matches) > 0:
                differences += 1
                print("%s: %s E1=%s, E2=%s" % (
                    doc, name, str([x.error.name for x in no_matches]),
                    str([x.error.name for x in no_matches_other])))

    print("Total of %d differences" % differences)

if __name__ == "__main__":
    main()
