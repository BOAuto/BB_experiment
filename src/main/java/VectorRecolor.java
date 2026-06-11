package main.java;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.Loader;

import java.awt.Color;
import java.awt.geom.Point2D;
import java.awt.geom.Line2D;
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
            File outputFile = new File(outputDir, "normalized_" + inputFile.getName());
            System.out.println("\n------------------------------------------------");
            System.out.println("Processing File: " + inputFile.getName());
            System.out.println("------------------------------------------------");

            try (PDDocument document = Loader.loadPDF(inputFile)) {
                for (int i = 0; i < document.getNumberOfPages(); i++) {
                    PDPage page = document.getPage(i);
                    
                    // Step 1: Extract all structural vector strokes into standalone Line2D objects
                    LineExtractorEngine engine = new LineExtractorEngine(page);
                    engine.processPage(page);
                    List<Line2D> independentLines = engine.getExtractedLines();

                    System.out.println(String.format("Page %d Analysis: Isolated %d completely independent line objects.", 
                            i + 1, independentLines.size()));

                    if (!independentLines.isEmpty()) {
                        // Step 2: Directly draw the unlinked lines back to the page as distinct green vector primitives
                        try (PDPageContentStream contentStream = new PDPageContentStream(
                                document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                            
                            contentStream.setStrokingColor(Color.GREEN);
                            contentStream.setLineWidth(1.0f);

                            for (Line2D line : independentLines) {
                                contentStream.moveTo((float) line.getX1(), (float) line.getY1());
                                contentStream.lineTo((float) line.getX2(), (float) line.getY2());
                                contentStream.stroke(); // Closes and draws each line entirely on its own
                            }
                        }
                    } else {
                        System.out.println(String.format("Page %d: No vector paths detected.", i + 1));
                    }
                }

                if (SAVE_DRAWINGS_ONLY) {
                    document.save(outputFile);
                    System.out.println(" -> Successfully saved output to: " + outputFile.getAbsolutePath());
                }

            } catch (IOException e) {
                System.err.println("Error processing " + inputFile.getName() + ": " + e.getMessage());
            }
        }
    }

    /**
     * High-Precision Graphics Engine that intercepts rendering paths 
     * and breaks compound structures down into standalone line objects.
     */
    private static class LineExtractorEngine extends PDFGraphicsStreamEngine {
        private final List<Line2D> extractedLines = new ArrayList<>();
        private Point2D currentPoint = new Point2D.Float(0, 0);

        protected LineExtractorEngine(PDPage page) { 
            super(page); 
        }

        public List<Line2D> getExtractedLines() { 
            return extractedLines; 
        }

        @Override
        public void moveTo(float x, float y) throws IOException { 
            // Updates the tracking cursor to a new structural starting position
            this.currentPoint = new Point2D.Float(x, y); 
        }

        @Override
        public void lineTo(float x, float y) throws IOException {
            // Instantly captures the path from the last point to the new point as an isolated line object
            extractedLines.add(new Line2D.Float((float) currentPoint.getX(), (float) currentPoint.getY(), x, y));
            this.currentPoint = new Point2D.Float(x, y);
        }

        @Override
        public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) throws IOException {
            // Deconstructs rectangle structures directly into 4 distinct, unlinked line boundaries
            extractedLines.add(new Line2D.Double(p0, p1));
            extractedLines.add(new Line2D.Double(p1, p2));
            extractedLines.add(new Line2D.Double(p2, p3));
            extractedLines.add(new Line2D.Double(p3, p0));
            this.currentPoint = p3;
        }

        @Override
        public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) throws IOException {
            // Converts curve anchor endpoints directly into a straight reference line for simpler linear tracking
            extractedLines.add(new Line2D.Float((float) currentPoint.getX(), (float) currentPoint.getY(), x3, y3));
            this.currentPoint = new Point2D.Float(x3, y3);
        }

        @Override public void strokePath() throws IOException {}
        @Override public void fillPath(int windingRule) throws IOException {}
        @Override public void fillAndStrokePath(int windingRule) throws IOException {}
        @Override public void drawImage(org.apache.pdfbox.pdmodel.graphics.image.PDImage pdImage) throws IOException {}
        @Override public void clip(int windingRule) throws IOException {}
        @Override public void closePath() throws IOException {}
        @Override public void endPath() throws IOException {}
        @Override public Point2D getCurrentPoint() throws IOException { return currentPoint; }
        @Override public void shadingFill(COSName shadingName) throws IOException {}
    }
}
