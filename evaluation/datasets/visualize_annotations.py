import datasets
from pdffigures_utils import scale_figure, draw_rectangle
from PIL import Image, ImageDraw
import argparse

"""
Script that displays a visualization of the annotations for a given dataset and document
"""


def main():
    parser = argparse.ArgumentParser(description='Evaluate a figure extractor')
    parser.add_argument("dataset",
                        choices=list(datasets.DATASETS.keys()))
    parser.add_argument("doc_id")
    parser.add_argument("-p", "--page", type=int)
    args = parser.parse_args()

    dataset = datasets.get_dataset(args.dataset)
    color_images = dataset.get_color_image_file_map()
    annotations = dataset.get_annotations("all")
    figures = annotations[args.doc_id]["figures"]
    for figure in figures:
        if args.page is not None and figure.page != args.page:
            continue
        image = Image.open(color_images[args.doc_id][figure.page])
        capt, region = scale_figure(figure, dataset.image_dpi)
        draw = ImageDraw.Draw(image)
        draw_rectangle(draw, region, (255,0,0), 4)
        draw_rectangle(draw, capt, (0,0,255), 4)
        del draw
        image.show()
        input((args.doc_id, str(figure)))
        image.close()

if __name__ == "__main__":
    main()
