import os
import glob
import fitz  # PyMuPDF

def process_pdf_layers(pdf_path, output_dir):
    filename = os.path.basename(pdf_path)
    print(f"Processing: {filename}...")

    # Define the 4 separate outputs we want to create
    types = ['text_only', 'images_only', 'drawings_only', 'combined']
    docs = {t: fitz.open(pdf_path) for t in types}

    # Process page by page across all document copies simultaneously
    for page_num in range(len(docs['combined'])):
        # Grab the same page from each document instance
        pages = {t: docs[t][page_num] for t in types}

        # 1. Gather Layout Data
        text_blocks = pages['combined'].get_text("blocks")
        image_info = pages['combined'].get_image_info()
        drawings = pages['combined'].get_drawings()

        # 2. Draw TEXT Boxes (Red) -> Applied to text_only and combined
        for block in text_blocks:
            rect = fitz.Rect(block[0], block[1], block[2], block[3])
            pages['text_only'].draw_rect(rect, color=[1, 0, 0], width=1.5)
            pages['combined'].draw_rect(rect, color=[1, 0, 0], width=1.5)

        # 3. Draw IMAGE Boxes (Blue) -> Applied to images_only and combined
        for img in image_info:
            rect = fitz.Rect(img["bbox"])
            pages['images_only'].draw_rect(rect, color=[0, 0, 1], width=1.5)
            pages['combined'].draw_rect(rect, color=[0, 0, 1], width=1.5)

        # 4. Draw DRAWINGS Boxes (Green) -> Applied to drawings_only and combined
        for draw in drawings:
            rect = draw["rect"]
            pages['drawings_only'].draw_rect(rect, color=[0, 0.6, 0], width=1)
            pages['combined'].draw_rect(rect, color=[0, 0.6, 0], width=1)

    # Save all 4 variations to the output directory
    for t, doc in docs.items():
        output_path = os.path.join(output_dir, f"{t}_{filename}")
        doc.save(output_path)
        doc.close()
        print(f" -> Saved: {output_path}")

def process_all_pdfs(input_dir, output_dir):
    os.makedirs(output_dir, exist_ok=True)
    pdf_files = glob.glob(os.path.join(input_dir, "*.pdf"))
    
    if not pdf_files:
        print(f"No PDF files found in '{input_dir}' directory.")
        return

    for pdf_path in pdf_files:
        process_pdf_layers(pdf_path, output_dir)

if __name__ == "__main__":
    process_all_pdfs("input", "output")
