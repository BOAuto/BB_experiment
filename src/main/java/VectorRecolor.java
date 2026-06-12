package main.java;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.cos.COSName; // <-- Added import for the missing method
import org.apache.pdfbox.Loader;

import java.awt.Color;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class VectorRecolor {

    private static final boolean SAVE_DRAWINGS_ONLY = true;

    public static void main(String[] args) {
        File inputDir = new File("pdfs");
        File outputDir = new File("output_artifacts");

        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        File[] files = inputDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".pdf"));

        if (files == null || files.length == 0) {
            System.out.println("No PDF files found in 'pdfs/' directory.");
            return;
        }

        for (File inputFile : files) {
            File outputFile = new File(outputDir, "drawings_only_" + inputFile.getName());
            System.out.println("Processing: " + inputFile.getName() + "...");

            try (PDDocument document = Loader.loadPDF(inputFile)) {
                for (int i = 0; i < document.getNumberOfPages(); i++) {
                    PDPage page = document.getPage(i);
                    
                    DrawingBoxEngine engine = new DrawingBoxEngine(page);
                    engine.processPage(page);
                    List<Rectangle2D> vectorBoxes = engine.getDetectedBoxes();

                    if (!vectorBoxes.isEmpty()) {
                        try (PDPageContentStream contentStream = new PDPageContentStream(
                                document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                            
                            contentStream.setStrokingColor(Color.GREEN);
                            contentStream.setLineWidth(1.0f);

                            for (Rectangle2D rect : vectorBoxes) {
                                contentStream.addRect((float) rect.getX(), (float) rect.getY(), 
                                                      (float) rect.getWidth(), (float) rect.getHeight());
                                contentStream.stroke();
                            }
                        }
                    }
                }

                if (SAVE_DRAWINGS_ONLY) {
                    document.save(outputFile);
                    System.out.println(" -> Saved to: " + outputFile.getAbsolutePath());
                }

            } catch (IOException e) {
                System.err.println("Error processing " + inputFile.getName() + ": " + e.getMessage());
            }
        }
    }

    private static class DrawingBoxEngine extends PDFGraphicsStreamEngine {
        private final List<Rectangle2D> detectedBoxes = new ArrayList<>();
        private Double minX, minY, maxX, maxY;

        protected DrawingBoxEngine(PDPage page) {
            super(page);
        }

        public List<Rectangle2D> getDetectedBoxes() {
            return detectedBoxes;
        }

        private void updateBounds(double x, double y) {
            if (minX == null) {
                minX = maxX = x;
                minY = maxY = y;
            } else {
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
        }

        private void flushPath() {
            if (minX != null) {
                detectedBoxes.add(new Rectangle2D.Double(minX, minY, maxX - minX, maxY - minY));
                minX = minY = maxX = maxY = null;
            }
        }

        @Override
        public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) throws IOException {
            updateBounds(p0.getX(), p0.getY());
            updateBounds(p2.getX(), p2.getY());
        }

        @Override
        public void moveTo(float x, float y) throws IOException { updateBounds(x, y); }

        @Override
        public void lineTo(float x, float y) throws IOException { updateBounds(x, y); }

        @Override
        public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) throws IOException {
            updateBounds(x1, y1);
            updateBounds(x3, y3);
        }

        @Override
        public void strokePath() throws IOException { flushPath(); }

        @Override
        public void fillPath(int windingRule) throws IOException { flushPath(); }

        @Override
        public void fillAndStrokePath(int windingRule) throws IOException { flushPath(); }

        @Override
        public void drawImage(org.apache.pdfbox.pdmodel.graphics.image.PDImage pdImage) throws IOException {}

        @Override
        public void clip(int windingRule) throws IOException { }

        @Override
        public void closePath() throws IOException { }

        @Override
        public void endPath() throws IOException { minX = minY = maxX = maxY = null; }

        @Override
        public Point2D getCurrentPoint() throws IOException { return new Point2D.Float(0, 0); }

        // --- THE FIX: Implementing the missing abstract method ---
        @Override
        public void shadingFill(COSName shadingName) throws IOException {
            // Left empty intentionally as we are only tracking vector outlines
        }
    }
}
