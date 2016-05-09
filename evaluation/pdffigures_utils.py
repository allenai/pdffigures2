from enum import Enum
from unicodedata import normalize
from PIL import Image, ImageDraw, ImageChops
from subprocess import check_output
import re


class FigureType(Enum):
    figure = 1
    table = 2

    def __str__(self):
        if self.name == "figure":
            return "Figure"
        else:
            return "Table"


def fig_type_to_str(figure_type):
    if figure_type == FigureType.figure:
        return "Figure"
    elif figure_type == FigureType.table:
        return "Table"
    else:
        raise ValueError("%s is not a valid figure type" % str(figure_type))


def str_to_fig_type(string):
    if string == "Figure":
        return FigureType.figure
    elif string == "Table":
        return FigureType.table
    else:
        raise ValueError("%s is not a valid figure type string" % string)


class Figure(object):

    @staticmethod
    def from_dict(data):
        return Figure(str_to_fig_type(data["figure_type"]), data["name"], data["page"],
                      data["dpi"], data["caption"], data["page_height"], data["page_width"],
                      data["caption_bb"], data["region_bb"])

    def as_dict(self):
        data = {}
        data.update(self.__dict__)
        data["figure_type"] = fig_type_to_str(data["figure_type"])
        return data

    def get_id(self):
        return self.figure_type, self.name, self.page

    def __init__(self, figure_type, name, page, dpi, caption, page_height=None, page_width=None,
                 caption_bb=None, region_bb=None):
        if not isinstance(figure_type, FigureType):
            raise ValueError()
        if page_width is None and page_height is not None or page_height is None and page_width is not None:
            raise ValueError()
        if page_width is not None and page_width <= 0:
            raise ValueError()
        if page_height is not None and page_height <= 0:
            raise ValueError()
        if (caption_bb is not None or region_bb is not None) and dpi is None:
            raise ValueError()
        if page is not None and page <= 0:
            raise ValueError()
        if caption is not None and not isinstance(caption, str):
            raise ValueError("Initialized with caption of type %s" % type(caption))
        if not isinstance(name, str):
            raise ValueError("Name was not a string")

        self.name = name
        self.figure_type = figure_type
        self.page = page
        self.page_height = page_height
        self.page_width = page_width
        self.dpi = dpi
        self.caption = caption
        self.caption_bb = caption_bb
        self.region_bb = region_bb

    def __str__(self):
        if self.page_width is not None:
            return ("%s%s:<page=%d, caption=%s, " +
                    "caption_bb=%s, region_bb=%s, dpi=%d>") % (
                "F" if self.figure_type == FigureType.figure else "T",
                self.name, self.page,
                        self.caption[:20], str(self.caption_bb),
                        str(self.region_bb), self.dpi)
        else:
            return "%s:<page=%s, caption=%s>" % (self.name, str(self.page), self.caption[:20])

    def __eq__(self, other):
        return isinstance(other, Figure) and self.__dict__ == other.__dict__


class Error(Enum):
    """
    Result on a Figure
    """
    """ Extraction not found in the gold annotation """
    false_positive = 1

    """ (Not scored) Figure not found in the gold annotation, but had no figure region """
    false_positive_no_region = 2

    """ Gold annotation that was not returned by the extractor """
    missing = 3

    """ Extraction that has a correct figure region but incorrect caption region """
    wrong_caption_box = 4

    """ Extraction that has a correct caption region but incorrect figure region """
    wrong_region_box = 5

    """ Extraction that has incorrect caption and figure regions """
    wrong_caption_and_region = 6

    """ Correct figure and caption region """
    correct = 7

    """ (Not scored) caption region was wrong but the extraction did not have a figure region """
    wrong_caption_no_region = 8

    """ (Not scored) caption region was correct but the extraction did not have a figure region """
    right_caption_no_region = 9

    @staticmethod
    def fromstring(string):
        return getattr(Error, string.upper(), None)

    def __str__(self):
        # so our string is "missing" not "Error.missing"
        return self.name


class EvaluatedFigure(object):
    """
    Figure we have graded, it could either be a 'true_figure' that did not have a
    corresponding extraction (missing), an extraction without a corresponding true figure (false positive))
    or a true figure and extracted figure that were paired together and graded for correctness.
    """

    def __init__(self, true_figure, extracted_figure, error, doc):
        if true_figure is None and extracted_figure is None:
            raise ValueError()
        if true_figure is not None and extracted_figure is not None:
            if (extracted_figure.page != true_figure.page or
                extracted_figure.name != true_figure.name or
                extracted_figure.figure_type != true_figure.figure_type):
                raise ValueError()
        if not isinstance(error, Error):
            raise ValueError()
        self.true_figure = true_figure
        self.extracted_figure = extracted_figure
        self.error = error
        if true_figure is not None:
            self.name = true_figure.name
            self.page = true_figure.page
            self.figure_type = true_figure.figure_type
        else:
            self.figure_type = extracted_figure.figure_type
            self.name = extracted_figure.name
            self.page = extracted_figure.page
        self.doc = doc
        if self.figure_type == FigureType.figure:
            self.name = "F%s p=%d" % (self.name, self.page)
        else:
            self.name = "T%s p=%d" % (self.name, self.page)

    def get_id(self):
        return self.doc, self.name

    def __eq__(self, other):
        return isinstance(other, EvaluatedFigure) and self.__dict__ == other.__dict__


class Evaluation(object):
    """
    A complete evaluation of an extractor on a dataset
    """

    # version 2: Added dataset_version and dataset_which
    # version 4: Switched to new Figure format
    # version 5: Add config
    # version 6: Changed error enum a bit
    # version 7: Switched figure 'number' -> 'name'
    # version 9: Which is now text not a `DatasetPartition` object
    # version 10: remove `which` parameter
    version = 10

    def __init__(self, dataset_name, dataset_version, extractor_name,
                 extractor_version, extractor_config,
                 evaluated_figures, compare_caption_text, doc_ids, timestamp):
        for fig in evaluated_figures:
            if not isinstance(fig, EvaluatedFigure):
                raise ValueError()
        if not isinstance(timestamp, float):
            raise ValueError("Got timestamp of type %s" % type(timestamp))
        if not isinstance(doc_ids, list):
            raise ValueError()

        self.dataset_name = dataset_name
        self.extractor_name = extractor_name
        self.extractor_version = extractor_version
        self.extractor_config = extractor_config
        self.evaluated_figures = evaluated_figures
        self.timestamp = timestamp
        self.dataset_name = dataset_name
        self.dataset_version = dataset_version
        self.compare_caption_text = compare_caption_text
        self.docs = doc_ids

    def __getstate__(self):
        state = self.__dict__
        state["version"] = self.version
        return state

    def __setstate__(self, state):
        version = state.get("version", 1)
        if version != self.version:
            print("WARNING: evaluation loaded with out-of-date version %d" % version)
        if "version" in state:
            del state["version"]
        self.__dict__.update(state)

    def __eq__(self, other):
        return isinstance(other, Evaluation) and self.__dict__ == other.__dict__


def box_overlap(box1, box2):
    bx, by, bx2, by2 = box1
    ox, oy, ox2, oy2 = box2

    assert bx < bx2 and by < by2
    assert ox < ox2 and oy < oy2

    if ox > bx2 or oy > by2 or bx > ox2 or by > oy2:
        return 0, 0, 0

    x, y = min(box1[0], box2[0]), min(box1[1], box2[1])
    x2, y2 = max(box1[2], box2[2]), max(box1[3], box2[3])
    area_union = (x2 - x) * (y2 - y)
    u = (x, y, x2, y2)

    x, y = max(box1[0], box2[0]), max(box1[1], box2[1])
    x2, y2 = min(box1[2], box2[2]), min(box1[3], box2[3])
    area_intersect = (x2 - x) * (y2 - y)

    overlap = area_intersect / float(area_union)
    assert 0.0 <= overlap <= 1.0
    return overlap, area_union, area_intersect


def box_overlaps(container, box):
    return not (
        container[0] > box[2] or
        container[1] > box[3] or
        container[2] < box[0] or
        container[3] < box[1]
        )


def box_contains(container, box, tol=0):
    return (container[0] <= (box[0] + tol) and
            container[1] <= (box[1] + tol) and
            container[2] >= (box[2] - tol) and
            container[3] >= (box[3] - tol))


def box_intersects(box, other, tol=0):
    return not ((box[2] < other[0] - tol) or (box[0] > other[2] + tol) or
      (box[3] < other[1] - tol) or (box[1] > other[3] + tol))


def crop_to_foreground(box, bw_image):
    """ Returns `box` cropped to non-whitespace in the given black and white image """
    blank = Image.new("1", bw_image.size)
    draw = ImageDraw.Draw(blank)
    draw.rectangle(box, fill=1)
    region_only = ImageChops.logical_and(blank, ImageChops.invert(bw_image))
    cropped = region_only.getbbox()
    return cropped


def scale_figure(figure, dpi):
    """ Returns the caption and region bbox of a `figure` when scaled to `dpi` """
    rescaling = dpi/figure.dpi
    if figure.caption_bb is not None:
        caption_box = [x*rescaling for x in figure.caption_bb]
    else:
        caption_box = None
    if figure.region_bb is not None:
        region_box = [x*rescaling for x in figure.region_bb]
    else:
        region_box = None
    return caption_box, region_box


def scale_and_crop_figure(figure, img, img_dpi):
    """
    Returns the caption and region bbox of a `figure` when scaled to `image_dpi` and
    whitespace cropped to `img`
    """
    caption_box, region_box = scale_figure(figure, img_dpi)
    if caption_box is not None:
        cropped = crop_to_foreground(caption_box, img)
        if cropped is not None:
            caption_box = cropped
    if region_box is not None:
        cropped = crop_to_foreground(region_box, img)
        if cropped is not None:
            region_box = cropped
    return caption_box, region_box


def draw_rectangle(draw, rectangle, color, thickness):
    for i in range(thickness):
        n = [rectangle[0] + i, rectangle[1] + i, rectangle[2] - i, rectangle[3] - i]
        draw.rectangle(n, outline=color)


def get_num_pages_in_pdf(pdf_filepath):
    """
    Returns the number of pages of the PDF found at `pdf_filepath`
    """
    args = ["pdfinfo", pdf_filepath]
    output = check_output(args).decode("UTF-8")
    lines = output.split("\n")
    for line in lines:
        if line.startswith("Pages:"):
            page = int(line[len("Pages:"):].strip())
            return page
    raise ValueError("No page returned?")


def get_pdf_text(pdf_filepath, page, box, dpi, tol=0):
    """
    Returns the text found on the given `page` and `box` containing coordinates specified at dpi `dpi`on the
    pdf at `pdf_filepath` while padding `box` by `tol`.
    """
    box = [round(x) for x in box]
    args = ["pdftotext", "-x", str(box[0] - tol), "-y", str(box[1] - tol), "-W",
            str(box[2] - box[0] + tol*2), "-H", str(box[3] - box[1] + tol*2), "-f", str(page),
            "-l", str(page), "-r", str(dpi), pdf_filepath, "-"]
    output = check_output(args)
    return output.decode("UTF-8").rstrip()


def normalize_string(string):
    # Remove spaces and hyphens, normalize unicode
    return normalize('NFKC', re.sub(r'\-|\s+', '', string))


def compare_captions(capt1, capt2):
    return normalize_string(capt1) == normalize_string(capt2)
