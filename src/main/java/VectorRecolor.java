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
            System.out.println("Processing Line Objects: " + inputFile.getName());
            System.out.println("------------------------------------------------");

            try (PDDocument document = Loader.loadPDF(inputFile)) {
                for (int i = 0; i < document.getNumberOfPages(); i++) {
                    PDPage page = document.getPage(i);
                    
                    // Step 1: Extract all vector strokes into standalone Line2D objects
                    LineExtractorEngine extractor = new LineExtractorEngine(page);
                    extractor.processPage(page);
                    List<Line2D> rawLines = extractor.getExtractedLines();

                    if (!rawLines.isEmpty()) {
                        List<Line2D> processedLines = new ArrayList<>();
                        int shortenedCount = 0;

                        // Step 2: Iterate over each line as an independent entity
                        for (Line2D line : rawLines) {
                            double x1 = line.getX1();
                            double y1 = line.getY1();
                            double x2 = line.getX2();
                            double y2 = line.getY2();

                            // TARGET RULE: Detect vertical lines (X coords match, Y delta > 4)
                            boolean isVertical = Math.abs(x1 - x2) < 0.5;
                            boolean isSubstantial = Math.abs(y1 - y2) > 4.0;

                            if (isVertical && isSubstantial) {
                                // Shorten the line's height to a minuscule 0.001 unit length
                                double shortenedY2 = y1 + (y2 > y1 ? 0.001 : -0.001);
                                processedLines.add(new Line2D.Double(x1, y1, x2, shortenedY2));
                                shortenedCount++;
                            } else {
                                // Keep all other lines exactly as they are
                                processedLines.add(line);
                            }
                        }

                        System.out.println(String.format("  Page %d: Total lines = %d | Shortened vertical lines = %d", 
                                i + 1, rawLines.size(), shortenedCount));

                        // Step 3: Draw the updated lines back to the page as distinct green vector primitives
                        try (PDPageContentStream contentStream = new PDPageContentStream(
                                document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                            
                            contentStream.setStrokingColor(Color.GREEN);
                            contentStream.setLineWidth(1.0f);

                            for (Line2D cleanLine : processedLines) {
                                contentStream.moveTo((float) cleanLine.getX1(), (float) cleanLine.getY1());
                                contentStream.lineTo((float) cleanLine.getX2(), (float) cleanLine.getY2());
                                contentStream.stroke();
                            }
                        }
                    } else {
                        System.out.println(String.format("  Page %d: No vector paths detected.", i + 1));
                    }
                }

                document.save(outputFile);
                System.out.println(" -> Successfully updated and saved: " + outputFile.getAbsolutePath());
            } catch (IOException e) {
                System.err.println("Error processing " + inputFile.getName() + ": " + e.getMessage());
            }
        }
    }

    // High-Precision Graphics Engine that safely breaks continuous shapes down into standalone lines
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
        public void moveTo(float x, float y) { 
            this.currentPoint = new Point2D.Float(x, y); 
        }

        @Override
        public void lineTo(float x, float y) {
            // Break the stroke into its own individual object entry
            extractedLines.add(new Line2D.Float((float) currentPoint.getX(), (float) currentPoint.getY(), x, y));
            this.currentPoint = new Point2D.Float(x, y);
        }

        @Override
        public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
            // Deconstruct an entire rectangle primitive into 4 independent, isolated line segments
            extractedLines.add(new Line2D.Double(p0, p1));
            extractedLines.add(new Line2D.Double(p1, p2));
            extractedLines.add(new Line2D.Double(p2, p3));
            extractedLines.add(new Line2D.Double(p3, p0));
        }

        @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
            // Convert curves to a straight anchor line segment for linear structural processing
            extractedLines.add(new Line2D.Float((float) currentPoint.getX(), (float) currentPoint.getY(), x3, y3));
            this.currentPoint = new Point2D.Float(x3, y3);
        }

        @Override public void strokePath() {}
        @Override public void fillPath(int windingRule) {}
        @Override public void fillAndStrokePath(int windingRule) {}
        @Override public void drawImage(org.apache.pdfbox.pdmodel.graphics.image.PDImage pdImage) {}
        @Override public void clip(int windingRule) {}
        @Override public void closePath() {}
        @Override public void endPath() {}
        @Override public Point2D getCurrentPoint() { return currentPoint; }
        @Override public void shadingFill(COSName shadingName) {}
    }
}
