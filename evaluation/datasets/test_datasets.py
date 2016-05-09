import unittest
import math
import datasets
from pdffigures_utils import get_num_pages_in_pdf


class TestDataset(unittest.TestCase):

    def test_pages_annotated_consistency(self):
        for dataset in datasets.DATASETS.values():
            dataset = dataset()
            pages_annotated = dataset.get_annotated_pages_map()
            if pages_annotated is None:
                continue
            pdf_file_map = dataset.get_pdf_file_map()
            annotations = dataset.get_annotations("all")
            docs = dataset.get_doc_ids("all")
            self.assertEqual(set(docs), pages_annotated.keys())
            for doc, pages in pages_annotated.items():
                filename = pdf_file_map[doc]
                self.assertTrue(len(pages) <= dataset.MAX_PAGES_TO_ANNOTATE)
                num_pages = get_num_pages_in_pdf(filename)
                self.assertTrue(num_pages >= max(pages) - 1)
                expected_pages = math.ceil(num_pages*dataset.PAGE_SAMPLE_PERCENT)
                expected_pages = min(expected_pages, dataset.MAX_PAGES_TO_ANNOTATE)
                self.assertTrue(len(pages) == expected_pages)

                if doc in annotations:
                    ann = annotations[doc]
                    self.assertEqual(set(ann["annotated_pages"]), set(pages))
                    for fig in ann["figures"]:
                        self.assertTrue(fig.page in pages)

    def test_consistency(self):
        for dataset in datasets.DATASETS.values():
            dataset = dataset()
            all_docs = set(dataset.get_doc_ids(datasets.DatasetPartition("all")))
            doc_map = dataset.get_pdf_file_map()
            self.assertEqual(len(all_docs - doc_map.keys()), 0)
            doc_map = dataset.get_color_image_file_map()
            if doc_map is not None:
                self.assertEqual(len(all_docs - doc_map.keys()), 0)
            doc_map = dataset.get_gray_image_file_map()
            if doc_map is not None:
                self.assertEqual(len(all_docs - doc_map.keys()), 0)

            documents = dataset.load_doc_ids(all_docs)
            self.assertEqual(all_docs, set([x.doc_id for x in documents]))
            for doc in documents:
                if doc.color_images is not None and doc.gray_images is not None:
                    self.assertEqual(doc.gray_images.keys(), doc.color_images.keys())
                pages_annotated = doc.pages_annotated
                for fig in doc.figures:
                    self.assertTrue(fig.page in pages_annotated)
            self.assertEqual(doc.pdffile.split("/")[-1][:-4], doc.doc_id)

if __name__ == '__main__':
    unittest.main()
