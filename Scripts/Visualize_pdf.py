import os
import glob
import fitz  # PyMuPDF

def process_all_pdfs(input_dir, output_dir):
    # Ensure output directory exists
    os.makedirs(output_dir, exist_ok=True)
    
    # Find all PDFs in the input folder
    pdf_files = glob.glob(os.path.join(input_dir, "*.pdf"))
    
    if not pdf_files:
        print(f"No PDF files found in '{input_dir}' directory.")
        return

    for pdf_path in pdf_files:
        filename = os.path.basename(pdf_path)
        output_path = os.path.join(output_dir, f"boxed_{filename}")
        print(f"Processing: {filename}...")
        
        doc = fitz.open(pdf_path)
        
        for page_num in range(len(doc)):
            page = doc[page_num]
            
            # 1. TEXT BOUNDING BOXES (Red)
            for block in page.get_text("blocks"):
                rect = fitz.Rect(block[0], block[1], block[2], block[3])
                page.draw_rect(rect, color=[1, 0, 0], width=1.5)
                
            # 2. IMAGE BOUNDING BOXES (Blue)
            for img in page.get_image_info():
                rect = fitz.Rect(img["bbox"])
                page.draw_rect(rect, color=[0, 0, 1], width=1.5)
                
            # 3. VECTOR GRAPHICS / TABLES (Green)
            for draw in page.get_drawings():
                page.draw_rect(draw["rect"], color=[0, 0.6, 0], width=1)

        doc.save(output_path)
        doc.close()
        print(f"Successfully created: {output_path}")

if __name__ == "__main__":
    process_all_pdfs("input", "output")
  
