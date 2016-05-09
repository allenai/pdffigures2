import argparse
import os
import tempfile
from os import listdir
from os import mkdir
from os.path import join, isdir

from requests import HTTPError

from datasets import datasets
from datasets.build_dataset_images import get_images
from datasets.datasets import DATASETS

"""
This script checks for missing PDFs or missing rasterized images and re-downloads or regenerates
any that are missing. Unless a file was only partially downloaded it can be safely restarted if it crashes.
"""


def download_from_urls(doc_id_to_url, output_dir):
    import requests
    already_have = 0
    if not isdir(output_dir):
        print("Making directory %s to store PDFs" % output_dir)
        mkdir(output_dir)
    else:
        for filename in listdir(output_dir):
            if not filename.endswith(".pdf"):
                raise ValueError("File %s id not a PDF file" % filename)
            doc_id = filename[:-4]
            if doc_id not in doc_id_to_url:
                raise ValueError("Document %s has an document id that was not recognized" % filename)
            already_have += 1
            del doc_id_to_url[doc_id]
    print("Already have %d documents, need to download %d" % (already_have, len(doc_id_to_url)))

    for i, (doc_id, url) in enumerate(sorted(doc_id_to_url.items())):
        print("Downloading %s (%d of %d)" % (doc_id, i + 1, len(doc_id_to_url)))
        r = requests.get(url, allow_redirects=True)
        try:
            r.raise_for_status()
        except HTTPError as e:
            # REMOVE THIS EXCEPT
            print(e)
            continue
        content = r.content
        if len(content) == 0:
            raise ValueError("Empty response for doc %s url=%s" % (doc_id, url))
        try:
            if content.decode("utf-8").startswith("<!DOCTYPE html>"):
                print(content)
                raise ValueError("Appeared to get an HTML file for doc %s url=%s" % (doc_id, url))
        except UnicodeDecodeError:
            pass
        # To ensure atomic writes, dump into a tmp file and then rename the tmp file
        with tempfile.NamedTemporaryFile(mode="wb", delete=False) as pdf_file:
            name = pdf_file.name
            pdf_file.write(content)
        r.close()
        os.rename(name, join(output_dir, doc_id + ".pdf"))


def setup():
    parser = argparse.ArgumentParser(description='Download pdfs and cache rasterized page images')
    parser.add_argument("-g", "--gray-images", help="Build grayscale images for each PDF "
                                                    "(used for cropping extractor output)", action="store_true")
    parser.add_argument("-c", "--color-images", help="Build color images for each PDF (used for debugging)",
                        action="store_true")
    args = parser.parse_args()

    for name, dataset in sorted(DATASETS.items()):
        print("*" * 10 + " SETTING UP DATASET: %s" % name + " " + "*" * 10)
        dataset = dataset()
        print("DOWNLOADING PDFS:")
        download_from_urls(dataset.get_urls(), dataset.pdf_dir)
        print("Done!")
        if args.gray_images:
            print("\nBUILDING GRAYSCALE IMAGES:")
            get_images(dataset.pdf_dir, dataset.page_images_gray_dir, dataset.image_dpi, True)
            print("Done!")
        if args.color_images:
            print("\nBUILDING COLOR IMAGES:")
            get_images(dataset.pdf_dir, dataset.page_images_color_dir, dataset.image_dpi, False)
            print("Done!")

if __name__ == "__main__":
    setup()
