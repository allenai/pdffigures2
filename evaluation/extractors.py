import json
import os
import tempfile
from os import remove, environ
from os.path import isdir, join, isfile, dirname
from shutil import which, rmtree
from subprocess import call, DEVNULL, check_output

from pdffigures_utils import Figure, FigureType, str_to_fig_type


class PDFFigures2(object):
    """
    The new scala based extractor. Environment variable "PDFFIGURES2_HOME" can be used to point
    towards the home directory of the figure extractor. For example:
    PDFFIGURES2_HOME=/Users/chris/pdffigures2/
    Otherwise the the extractor is look for in the parent directory of this file
    """

    NAME = "pdffigures2"
    ENVIRON_VAR = "PDFFIGURES2_HOME"

    def __init__(self):
        if self.ENVIRON_VAR not in environ:
            self.extractor_home = dirname(dirname(__file__))
        else:
            self.extractor_home = environ[self.ENVIRON_VAR]
        if not isdir(self.extractor_home):
            raise ValueError("Figure extractor home (%s) not found" % self.extractor_home)
        self.version = None
        self.extractions = None

    def get_config(self):
        pass

    def get_version(self):
        if self.version is None:
            output = check_output(["sbt", "-no-colors", "version"], cwd=self.extractor_home)
            self.version = output.decode("utf-8").strip().split("\n")[-1].split("[info] ")[-1]
        return self.version

    def time(self, pdf_filenames, extract_images=False, verbose=False):
        tmpdir = tempfile.mkdtemp()
        try:
            if extract_images:
                cli_args = " ".join(["run", ",".join(pdf_filenames), "-c", "-m", tmpdir + "/fig",
                                     "-d", tmpdir + "/", "-e", "-q"])
            else:
                cli_args = " ".join(["run", ",".join(pdf_filenames), "-c", "-d", tmpdir + "/", "-e", "-q"])

            # -Dsun.java2d.cmm=sun.java2d.cmm.kcms.KcmsServiceProvider is important to get
            # good performance rendering image heavy pdfs (see https://pdfbox.apache.org/2.0/getting-started.html)
            args = ["sbt", "-Dsun.java2d.cmm=sun.java2d.cmm.kcms.KcmsServiceProvider", cli_args]
            exit_code = call(args, cwd=self.extractor_home)
            if exit_code != 0:
                raise ValueError("Non-zero exit status %d, call:\n%s" % (exit_code,
                                                                     " ".join(args)))
        finally:
            rmtree(tmpdir)

    def start_batch(self, pdf_filenames):
        tmpdir = tempfile.mkdtemp()
        try:
            # TODO it would be nice remove SBT's logging from reaching STDOUT
            extractions = {}
            cli_args = " ".join(["run", ",".join(pdf_filenames),
                                 "-c", "-d", tmpdir + "/", "-e", "-q"])
            args = ["sbt", "-Dsun.java2d.cmm=sun.java2d.cmm.kcms.KcmsServiceProvider", cli_args]
            exit_code = call(args, cwd=self.extractor_home)
            if exit_code != 0:
                raise ValueError("Non-zero exit status %d, call:\n%s" %
                                 (exit_code, " ".join(args)))
            for filename in pdf_filenames:
                doc_id = filename[:filename.rfind(".")].split("/")[-1]
                extractions[doc_id] = self.load_json(join(tmpdir, doc_id + ".json"))
            self.extractions = extractions
        finally:
            rmtree(tmpdir)

    def load_json(self, output_file):
        figs = []
        if isfile(output_file):
            with open(output_file) as f:
                loaded_figs = json.load(f)
            for fig in loaded_figs["figures"] + loaded_figs["regionless-captions"]:
                if "regionBoundary" in fig:
                    caption = fig["caption"]
                    bb = fig["regionBoundary"]
                    region_bb = [bb["x1"], bb["y1"], bb["x2"], bb["y2"]]
                    bb = fig["captionBoundary"]
                    caption_bb = [bb["x1"], bb["y1"], bb["x2"], bb["y2"]]
                else:
                    bb = fig["boundary"]
                    caption_bb = [bb["x1"], bb["y1"], bb["x2"], bb["y2"]]
                    caption = fig["text"]
                    region_bb = None
                # For some reason (maybe due to text location issues in PDFBox?) the caption bounding box
                # is consistently just a little too small relative to our annotated caption bounding box.
                # It seems fair to account for this by fractionally expanding the returned bounding box
                caption_bb[1] -= 3
                caption_bb[0] -= 3
                caption_bb[2] += 3
                caption_bb[3] += 3
                figs.append(Figure(
                    figure_type=str_to_fig_type(fig["figType"]),
                    name=fig["name"],
                    page=fig["page"] + 1,
                    dpi=72.0,
                    caption=caption,
                    caption_bb=caption_bb,
                    region_bb=region_bb))
        return figs

    def get_extractions(self, pdf_filepath, dataset, doc_id):
        return self.extractions[doc_id]


class PDFFigures(object):
    """
    The original C++ based pffigures program. Requires the CLI tool `pdffigures` to be in PATH
    """

    NAME = "pdffigures"

    def __init__(self):
        if which("pdffigures") is None:
            raise ValueError("Could not find executable for `pdffigures`")

    def get_config(self):
        pass

    def get_version(self):
        return check_output(["pdffigures", "--version"]).decode("UTF-8").strip()

    def time(self, pdf_filenames, extract_images=False, verbose=False):
        output_dir = tempfile.mkdtemp()
        try:
            args = ["pdffigures", "-i", "-m", "-j", join(output_dir, "output.json")]
            if extract_images:
                args += ["-o", output_dir]
            for filename in pdf_filenames:
                exit_code = call(args + [filename], stderr=DEVNULL, stdout=DEVNULL)
                if exit_code != 0:
                    raise ValueError(" ".join(args))
        finally:
            rmtree(output_dir)

    def start_batch(self, pdf_filenames):
        pass

    def get_extractions(self, pdf_filepath, dataset, doc_id):
        # Shell out to pdffigures, the read in JSON output and use it to build `Figure` objects
        handle, filename = tempfile.mkstemp()
        try:
            args = ["pdffigures", "-i", "-m", "-j", filename, pdf_filepath]
            callret = call(args, stderr=DEVNULL, stdout=DEVNULL)
            if callret != 0:
                raise ValueError("Call %s had error code %d" % (" ".join(args), callret))
            extractions = []
            with open(filename + ".json") as f:
                figure_data = json.load(f)
        finally:
            os.close(handle)
            remove(filename)
        for data in figure_data:
            if data["Type"][0] == "F":
                fig_type = FigureType.figure
            elif data["Type"][0] == "T":
                fig_type = FigureType.table
            else:
                raise ValueError()
            fig = Figure(
                figure_type=fig_type,
                name=str(data["Number"]),
                region_bb=data["ImageBB"],
                caption_bb=data["CaptionBB"],
                caption=data["Caption"],
                page=int(data["Page"]),
                page_height=data["Height"],
                page_width=data["Width"],
                dpi=data["DPI"],
                )
            extractions.append(fig)
        return extractions


EXTRACTORS = {
  PDFFigures.NAME: PDFFigures,
  PDFFigures2.NAME: PDFFigures2
}


def get_extractor(name):
    return EXTRACTORS[name]()
