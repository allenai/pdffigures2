import os
import subprocess

# Base directory where the PDFs are located
base_pdf_dir = "/bigdata/preston/data/pdfs"

# Base output directory where the processed files will be stored
output_base = "/bigdata/preston/output"

# Function to create necessary output directories if they don't exist
def ensure_directories_exist(paths):
    for path in paths:
        os.makedirs(path, exist_ok=True)

# Function to check if processing should be skipped for a given category
def should_skip_category(stat_dir, images_dir, data_dir):
    # Example check: Skip if statistics file already exists
    return os.path.exists(os.path.join(stat_dir, "stat_file.json"))

# Function to process each category within the sources
def process_sources_and_categories(base_pdf_dir, output_base):
    for source in os.listdir(base_pdf_dir):
        source_dir = os.path.join(base_pdf_dir, source)
        if os.path.isdir(source_dir):
            for category in os.listdir(source_dir):
                category_dir = os.path.join(source_dir, category, "pdfs")  # assuming PDFs are in a 'pdfs' subdirectory
                if os.path.isdir(category_dir):
                    stat_dir = os.path.join(output_base, source, category, "statistics")
                    images_dir = os.path.join(output_base, source, category, "images")
                    data_dir = os.path.join(output_base, source, category, "data")
                    ensure_directories_exist([stat_dir, images_dir, data_dir])
                    
                    if should_skip_category(stat_dir, images_dir, data_dir):
                        print(f"Skipping already processed category: {category} in source: {source}")
                        continue  # Skip this category
                    
                    command = [
                        'sbt',
                        f'"runMain org.allenai.pdffigures2.FigureExtractorBatchCli {category_dir}',
                        f'-s {os.path.join(stat_dir, "stat_file.json")}',
                        f'-m {images_dir}/',
                        f'-d {data_dir}/"'
                    ]
                    full_command = ' '.join(command)
                    print(f"Processing category: {category} in source: {source}")
                    subprocess.run(full_command, shell=True)

if __name__ == "__main__":
    process_sources_and_categories(base_pdf_dir, output_base)
    print("Batch processing complete.")
