import argparse
from os import listdir, mkdir
from os.path import join, isdir
from subprocess import call
import sys
import datasets
from shutil import which


"""
Script to use pdftoppm to turn the pdfs into single images per page
"""


def get_images(pdf_dir, output_dir, dpi, mono=True):
    if which("pdftoppm") is None:
        raise ValueError("Requires executable pdftopmm to be on the PATH")

    if not isdir(output_dir):
        print("Making %s to store rasterized PDF pages" % output_dir)
        mkdir(output_dir)

    if not isdir(pdf_dir):
        raise ValueError(pdf_dir + " is not a directory")

    pdf_doc_ids = [x.split(".pdf")[0] for x in listdir(pdf_dir)]

    already_have = set()
    for filename in listdir(output_dir):
        if "-page" not in filename:
            raise ValueError()
        doc_id = filename.split("-page")[0]
        if doc_id not in pdf_doc_ids:
            raise ValueError("doc id %s in output dir not found in pdfs" % doc_id)
        already_have.add(doc_id)

    if len(already_have) != 0:
        print("Already have %d docs" % len(already_have))

    num_pdfs = len(listdir(pdf_dir))
    for (i, pdfname) in enumerate(listdir(pdf_dir)):
        if not pdfname.endswith(".pdf"):
            raise ValueError()
        doc_id = pdfname[:-4]
        if doc_id in already_have:
            continue
        print("Creating images for pdf %s (%d / %d)" % (pdfname, i + 1, num_pdfs))
        if (mono):
            args = ["pdftoppm", "-gray", "-r", str(dpi),
                  "-aa", "no", "-aaVector", "no", "-cropbox",
                  join(pdf_dir, pdfname), join(output_dir, doc_id + "-page")]
        else:
            args = ["pdftoppm", "-jpeg", "-r", str(dpi), "-cropbox",
                  join(pdf_dir, pdfname), join(output_dir, doc_id + "-page")]
        retcode = call(args)
        if retcode != 0:
            raise ValueError("Bad return code for <%s> (%d)", " ".join(args), retcode)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description='Cache rasterized page images for a dataset')
    parser.add_argument("dataset", choices=datasets.DATASETS.keys(), help="target dataset")
    parser.add_argument("color", choices=["gray", "color"], help="kind of images to render")
    args = parser.parse_args()

    dataset = datasets.get_dataset(args.dataset)
    print("Running on dataset: " + dataset.name)
    if args.color == "gray":
        get_images(dataset.pdf_dir, dataset.page_images_gray_dir,
                   dataset.IMAGE_DPI, True)
    elif args.color == "color":
        get_images(dataset.pdf_dir, dataset.page_images_color_dir,
                   dataset.COLOR_IMAGE_DPI, False)
    else:
        exit(1)
