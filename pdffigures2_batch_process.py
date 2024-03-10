import os
import subprocess

# Set the base directory where the source PDFs are located.
base_pdf_dir = "/bigdata/preston/data/pdfs"

# Set the base directory where the processed output files will be saved.
output_base = "/bigdata/preston/output"

def ensure_directories_exist(paths):
    """
    Create any missing directories in the given list of paths.
    """
    for path in paths:
        os.makedirs(path, exist_ok=True)  # This will create the directory if it does not exist.

def should_skip_category(stat_dir, images_dir, data_dir):
    """
    Determine if a category has already been processed based on the existence of a stats file.
    
    Args:
    - stat_dir: Directory path where statistics files are saved.
    - images_dir: Directory path where image files are saved.
    - data_dir: Directory path where data files are saved.
    
    Returns:
    - Boolean: True if the category should be skipped, False otherwise.
    """
    # Check if the statistics file already exists, indicating processing is complete.
    return os.path.exists(os.path.join(stat_dir, "stat_file.json"))

def process_sources_and_categories(base_pdf_dir, output_base):
    """
    Process each category of PDFs within each source directory, extracting figures and statistics.
    
    Args:
    - base_pdf_dir: The base directory containing source PDFs.
    - output_base: The base directory where processed outputs should be saved.
    """
    # Iterate through each source directory in the base PDF directory.
    for source in os.listdir(base_pdf_dir):
        source_dir = os.path.join(base_pdf_dir, source)
        if os.path.isdir(source_dir):
            # Iterate through each category directory within the source.
            for category in os.listdir(source_dir):
                category_dir = os.path.join(source_dir, category, "pdfs")  # PDFs are stored in a 'pdfs' subdirectory within each category.
                if os.path.isdir(category_dir):
                    # Define paths for the output statistics, images, and data.
                    stat_dir = os.path.join(output_base, source, category, "statistics")
                    images_dir = os.path.join(output_base, source, category, "images")
                    data_dir = os.path.join(output_base, source, category, "data")
                    
                    # Ensure the output directories exist.
                    ensure_directories_exist([stat_dir, images_dir, data_dir])
                    
                    # Skip processing this category if it has already been done.
                    if should_skip_category(stat_dir, images_dir, data_dir):
                        print(f"Skipping already processed category: {category} in source: {source}")
                        continue
                    
                    # Construct the command to run the figure extraction using sbt and the pdffigures2 library.
                    command = [
                        'sbt',
                        f'"runMain org.allenai.pdffigures2.FigureExtractorBatchCli {category_dir}',
                        f'-s {os.path.join(stat_dir, "stat_file.json")}',
                        f'-m {images_dir}/',
                        f'-d {data_dir}/"'
                    ]
                    full_command = ' '.join(command)
                    print(f"Processing category: {category} in source: {source}")
                    
                    # Execute the command.
                    subprocess.run(full_command, shell=True)

# When the script is run, process the categories and sources.
if __name__ == "__main__":
    process_sources_and_categories(base_pdf_dir, output_base)
    print("Batch processing complete.")
