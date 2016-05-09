import re
from genericpath import isfile
from os import environ, listdir, mkdir, remove
from os.path import isdir, join, dirname
from shutil import rmtree, copy
from subprocess import call
import xml.etree.ElementTree as ET
import json
import tempfile

"""
File for section extractors. Section extractor are python objects that have 'get_sections'
method which takes] as input a list of PDF files and outputs a dictionary of
document_name -> list of sections". An example output could be:
{
   "nips11_3": ["Introduction", "Conclusion"]
}
"""


class FigureExtractor(object):
    name = "pdffigures2"

    def __init__(self,):
        if "PDFFIGURES2_HOME" not in environ:
            home = dirname(dirname(__file__))
        else:
            home = environ["PDFFIGURES2_HOME"]

        self.home = home
        self.scratch_dir = "/tmp/_figures_sections"
        if not isdir(self.scratch_dir):
            mkdir(self.scratch_dir)
        else:
            for filename in listdir(self.scratch_dir):
                remove(join(self.scratch_dir, filename))

    def get_sections(self, doc_list):
        args = ["sbt", "run " +
                ",".join(doc_list) + " -q -g " + self.scratch_dir + "/"]
        exit_code = call(args, cwd=self.home)
        if exit_code != 0:
            raise ValueError("Non-zero exit status %d, call:\n%s" % (exit_code,
                                                                    " ".join(args)))
        sections = {}
        for filename in listdir(self.scratch_dir):
            with open(join(self.scratch_dir, filename)) as f:
                data = json.load(f)
                doc_id = filename.split("/")[-1][:-5]
                doc_sections = []
                for section in data["sections"]:
                    if "title" in section:
                        doc_sections.append(section["title"]["text"])
            sections[doc_id] = doc_sections
        return sections


class Parscit(object):
    # Parscit extractor, note I have yet to get this to work well, maybe
    # because have only tried it with pdftotext as input
    name = "parscit"

    def __init__(self):
        self.cache = "parscit_cache"
        if "PARSCIT" not in environ:
            raise ValueError("Enviroment variable PARSCIT must point to PARSCIT source")
        self.script = join(environ["PARSCIT"], "bin", "citeExtract.pl")
        if not isfile(self.script):
            raise ValueError()

    def build_cache(self, doc_list):
        if not isdir(self.cache):
            print("Cache %s not found, rebuilding" % self.cache)
            mkdir(self.cache)

        files_in_cache = set()
        for filename in listdir(self.cache):
            files_in_cache.add(filename[:-4])

        with tempfile.NamedTemporaryFile() as tmp_file_handle:
            tmp_file = tmp_file_handle.name
            for filename in doc_list:
                doc_id = filename[:-4]
                if doc_id in files_in_cache:
                    continue
                print("Running parscit on file %s" % doc_id)
                filepath = join(doc_list, filename)
                pdftotext_args = ["pdftotext", filepath, tmp_file]
                if call(pdftotext_args) != 0:
                    raise ValueError("Call to pdftotext failed: <%s>", " ".join(pdftotext_args))
                output_file = join(self.cache, filename[:-4] + ".xml")
                args = ["perl", self.script, "-m", "extract_section", "-i", "raw",
                        tmp_file, output_file]
                if call(args) != 0:
                    raise ValueError("Call to parscit failed: <%s>", " ".join(args))

    def get_sections(self, doc_list):
        self.build_cache(doc_list)
        doc_ids_to_parse = set(x.split("/")[-1][:-4] for x in doc_list)
        sections = {}
        for doc_id in doc_ids_to_parse:
            cache_filename = join(self.cache, doc_id + ".xml")
            sections[doc_id] = self.get_sections_from_xml(cache_filename)
        return sections

    def get_sections_from_xml(self, filename):
        tree = ET.parse(filename)
        root = tree.getroot()
        sections = []
        section_nodes = root.findall(".//sectionHeader")
        for section_node in section_nodes:
            sections.append(section_node.text.strip())
        return sections


class Grobid(object):
    # NOTE: this was tested with Grobid 4.0, Grobid > 4.0 has a different format
    # for numbered sections and will not get evaluated correctly
    number_regex = re.compile("[0-9]+([0-9]+\.)*")
    name = "grobid"

    def __init__(self, numbered_only=False, search_trash=False):
        self.search_trash = search_trash
        self.numbered_only = numbered_only
        if "GROBID" not in environ:
            raise ValueError("Enviroment variable GROBID must point to grobid source")
        grobid = environ["GROBID"]
        target_dir = join(grobid, "grobid-core", "target")
        one_jar = [x for x in listdir(target_dir) if x.endswith("one-jar.jar")]
        if len(one_jar) == 0:
            raise ValueError("one-jar jar file not found in %s (Grobid not compiled?)" % target_dir)
        if len(one_jar) != 1:
            raise ValueError("Multiple on-jar jars? Found %s" % str(one_jar))
        one_jar = one_jar[0]
        self.grobid_jar = join(target_dir, one_jar)
        self.grobid_home = join(grobid, "grobid-home")
        assert "grobid-core-" in one_jar
        self.version = one_jar[one_jar.find("grobid-core-") + 12:]
        self.version = self.version[:self.version.find(".one-jar.jar")]
        self.cache = "grobid_cache_" + self.version

    def build_cache(self, doc_list):
        if not isdir(self.cache):
            print("Cache %s not found, rebuilding" % self.cache)
            mkdir(self.cache)

        files_in_cache = set()
        for filename in listdir(self.cache):
            if not filename.endswith(".tei.xml"):
                raise ValueError("Unexpected file in cached %s" % filename)
            files_in_cache.add(filename[:-8])

        doc_id_to_file = {x.split("/")[-1][:-4]:x  for x in doc_list}

        if len(doc_id_to_file.keys() - files_in_cache) > 0:
            # Grobid needs an input directory, not a list of files, so make a
            # temp one and move the needed files into it
            tmpdir = tempfile.mkdtemp()
            try:
                to_add = doc_id_to_file.keys() - files_in_cache
                for doc_id in to_add:
                    filename = doc_id_to_file[doc_id]
                    doc = filename.split("/")[-1]
                    copy(filename, join(tmpdir, doc))
                args = ["java", "-Xmx1024m", "-jar", self.grobid_jar, "-gH", self.grobid_home,
                        "-dIn", tmpdir, "-dOut", self.cache, "-exe", "processFullText", "-ignoreAssets"]
                print(" ".join(args))
                if call(args) != 0:
                    raise ValueError("Call to grobid failed: <%s>", " ".join(args))
            finally:
                rmtree(tmpdir)

    def get_sections(self, doc_list):
        self.build_cache(doc_list)
        doc_ids_to_parse = set(x.split("/")[-1][:-4] for x in doc_list)
        sections = {}
        for doc_id in doc_ids_to_parse:
            cache_filename = join(self.cache, doc_id + ".tei.xml")
            sections[doc_id] = self.get_sections_from_xml(cache_filename)
        return sections

    def get_sections_from_xml(self, filename):
        tree = ET.parse(filename)
        root = tree.getroot()
        sections = []
        section_nodes = list(root.findall(".//{http://www.tei-c.org/ns/1.0}body/{http://www.tei-c.org/ns/1.0}div"))
        for section_node in section_nodes:
            header = section_node.find("{http://www.tei-c.org/ns/1.0}head")
            if header is None:
                continue
            text = header.text
            if text is None:
                continue
            if self.numbered_only and self.number_regex.match(text) is None:
                continue
            if not any(c.isalpha() for c in text) or len(text) == 1:
                # Prune obviously bad headers
                continue
            sections.append(text)
        return sections


EXTRACTORS = {
    Parscit.name: Parscit,
    Grobid.name: Grobid,
    Grobid.name+"-numbered": lambda : Grobid(True),
    FigureExtractor.name : FigureExtractor
}


def get_extractor(name):
    if name in EXTRACTORS:
        return EXTRACTORS[name]()
    else:
        raise ValueError("No extractor named %s" % name)
