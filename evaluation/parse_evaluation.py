import argparse
from collections import Counter, defaultdict
import pickle
from pdffigures_utils import *
from datasets import datasets
from random import shuffle

"""
Script for show errors or printing stats for a saved Evaluation object
"""


def get_num_labels(error_counts):
    return (sum(error_counts.values()) -
            error_counts[Error.false_positive] -
            error_counts[Error.false_positive_no_region])


def get_pr(error_counts, caption_only):
    if not caption_only:
        correct_errs = {Error.correct}
        not_returned_errs = {Error.missing,
                             Error.false_positive_no_region,
                             Error.wrong_caption_no_region,
                             Error.right_caption_no_region}
    else:
        correct_errs = {Error.correct, Error.wrong_region_box,
                        Error.right_caption_no_region}
        not_returned_errs = {Error.missing}

    num_labels = get_num_labels(error_counts)
    num_returned = sum(error_counts.values()) - sum([error_counts[x] for x in not_returned_errs])
    num_correct = sum(error_counts[x] for x in correct_errs)

    if num_correct == 0:
        return 0, 0, 0

    precision = num_correct / num_returned
    recall = num_correct / num_labels
    if (precision + recall) == 0:
        f1 = 0
    else:
        f1 = 2 * (precision * recall) / (precision + recall)
    return precision, recall, f1


def print_pr(evaluation, caption_only):
    error_counts_tables = Counter()
    error_counts_figures = Counter()
    for fig in evaluation.evaluated_figures:
        if fig.figure_type == FigureType.figure:
            error_counts_figures[fig.error] += 1
        elif fig.figure_type == FigureType.table:
            error_counts_tables[fig.error] += 1
        else:
            raise RuntimeError()

    def print_count_table(table):
        for k, v in table.items():
            print("%s: %d" % (str(k)[str(k).find(".") + 1:], v))
        print()

    num_tables = get_num_labels(error_counts_tables)
    num_figs = get_num_labels(error_counts_figures)

    print("TABLES (%d examples)" % num_tables)
    print_count_table(error_counts_tables)
    print("Precision: %0.3f, Recall: %0.3f, F1: %0.3f" %
          get_pr(error_counts_tables, caption_only))

    print("FIGURES (%d examples)" % num_figs)
    print_count_table(error_counts_figures)
    print("Precision: %0.3f, Recall: %0.3f, F1: %0.3f" %
          get_pr(error_counts_figures, caption_only))

    print("BOTH (%d examples)" % (num_figs + num_tables))
    print_count_table(error_counts_tables + error_counts_figures)
    print("Precision: %0.3f, Recall: %0.3f, F1: %0.3f" %
          get_pr(error_counts_tables + error_counts_figures, caption_only))


def list_errors(evaluation):
    per_doc = defaultdict(list)
    for fig in evaluation.evaluated_figures:
        per_doc[fig.doc].append(fig)
    for doc in sorted(per_doc):
        figs = per_doc[doc]
        figs = [x for x in figs if x.error != Error.correct]
        for fig in sorted(figs, key=lambda x: x.page):
            print("%s for figure %s: %s page: %d" % (
                str(fig.error), doc, str(fig.name), fig.page))


def show_errors(evaluation, random_order, errors_to_show):
    dataset = datasets.get_dataset(evaluation.dataset_name)
    dpi = dataset.COLOR_IMAGE_DPI
    color_images = dataset.get_color_image_file_map()
    per_doc = defaultdict(list)
    for fig in evaluation.evaluated_figures:
        per_doc[fig.doc].append(fig)
    if random_order:
        docs = list(per_doc.keys())
        shuffle(docs)
    else:
        docs = sorted(list(per_doc.keys()))
    for doc in docs:
        figs = per_doc[doc]
        figs = [x for x in figs if x.error in errors_to_show]
        for fig in sorted(figs, key=lambda x: x.page):
            color_image = Image.open(color_images[doc][fig.page])
            draw = ImageDraw.Draw(color_image)
            if fig.extracted_figure is not None:
                e_caption, e_region = scale_figure(fig.extracted_figure, dpi)
                if e_caption is not None:
                    draw_rectangle(draw, e_caption, (0,0,255), 6)
                if e_region is not None:
                    draw_rectangle(draw, e_region, (0,255,0), 6)
            if fig.true_figure is not None:
                t_caption, t_region = scale_figure(fig.true_figure, dpi)
                if fig.error in {Error.wrong_caption_box, Error.wrong_caption_and_region,
                                 Error.wrong_caption_no_region, Error.false_positive_no_region,
                                 Error.missing}:
                    draw_rectangle(draw, t_caption, (255, 0, 0), 6)
                if fig.error in {Error.missing, Error.wrong_region_box,
                                 Error.wrong_caption_and_region, Error.wrong_caption_no_region,
                                 Error.right_caption_no_region}:
                    draw_rectangle(draw, t_region, (255, 0, 0), 6)

            print("%s for figure %s: %s page: %d" % (str(fig.error), doc, fig.name, fig.page))
            del draw
            color_image.show()
            input()
            color_image.close()


def main():
    parser = argparse.ArgumentParser(description='Display an evaluation')
    parser.add_argument("evaluation")
    parser.add_argument("-s", "--show-errors", nargs="?", const="all",
                        choices=[x.name for x in Error] + ["all"])
    parser.add_argument("-t", "--list-errors", action='store_true')
    parser.add_argument("-d", "--doc")
    parser.add_argument("-f", "--type", choices=["Tables", "Figures", "T", "F"])
    parser.add_argument("-r", "--random-order", action='store_true')
    parser.add_argument("-c", "--caption-evaluation", action='store_true')
    args = parser.parse_args()

    with open(args.evaluation, "rb") as f:
        evaluation = pickle.load(f)

    print("Extractor: %s (version=%s)" % (evaluation.extractor_name, evaluation.extractor_version))
    print("Dataset: %s (version=%s)" % (evaluation.dataset_name, evaluation.dataset_version))

    if args.doc is not None:
        evaluation.evaluated_figures = [x for x in evaluation.evaluated_figures if x.doc == args.doc]
    if args.type is not None:
        figure_type = FigureType.figure if args.type[0] == "F" else FigureType.table
        evaluation.evaluated_figures = [x for x in evaluation.evaluated_figures if x.figure_type == figure_type]

    print_pr(evaluation, args.caption_evaluation)
    if args.show_errors is not None:
        if args.show_errors == "all":
            if args.caption_evaluation:
                correct_errs = {Error.correct, Error.right_caption_no_region}
                errors_to_show = [x for x in Error if x not in correct_errs]
            else:
                errors_to_show = [x for x in Error if x != Error.correct]
        else:
            errors_to_show = [Error[args.show_errors]]
        show_errors(evaluation, args.random_order, errors_to_show)

    if args.list_errors:
        list_errors(evaluation)

if __name__ == "__main__":
    main()
