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
import java.util.Collections;
import java.util.Comparator;
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
                        List<Line2D> verticalLines = new ArrayList<>();
                        
                        // Separate vertical lines from horizontal/other structural lines
                        for (Line2D line : rawLines) {
                            if (Math.abs(line.getX1() - line.getX2()) < 0.5) {
                                verticalLines.add(line);
                            } else {
                                processedLines.add(line); // Horizontal lines are safe
                            }
                        }

                        // Step 2: Sort vertical lines by X coordinate to find adjacent duplicates/flows
                        Collections.sort(verticalLines, Comparator.comparingDouble(Line2D::getX1));
                        
                        int shortenedCount = 0;
                        float proximityThreshold = 8.0f; // Max distance between duplicate lines

                        for (int v = 0; v < verticalLines.size(); v++) {
                            Line2D currentVert = verticalLines.get(v);
                            boolean isDuplicateBorder = false;

                            // Check neighbors within a sliding window to see if it's a layout line or duplicate flow line
                            for (int n = v + 1; n < verticalLines.size(); n++) {
                                Line2D neighbor = verticalLines.get(n);
                                if (neighbor.getX1() - currentVert.getX1() > proximityThreshold) {
                                    break; // Too far away, stop checking this window
                                }

                                // If they overlap vertically and are very close horizontally, it's a target line
                                double verticalOverlap = Math.min(currentVert.getY2(), neighbor.getY2()) - Math.max(currentVert.getY1(), neighbor.getY1());
                                if (verticalOverlap > 2.0) {
                                    isDuplicateBorder = true;
                                    break;
                                }
                            }

                            // Step 3: Apply the line mutation safely on separate objects
                            if (isDuplicateBorder) {
                                double y1 = currentVert.getY1();
                                double y2 = currentVert.getY2();
                                double shortenedY2 = y1 + (y2 > y1 ? 0.001 : -0.001);
                                processedLines.add(new Line2D.Double(currentVert.getX1(), y1, currentVert.getX2(), shortenedY2));
                                shortenedCount++;
                            } else {
                                processedLines.add(currentVert); // Keep valid data-table vertical line
                            }
                        }

                        System.out.println(String.format("  Page %d: Total raw lines = %d | Shortened target lines = %d", 
                                i + 1, rawLines.size(), shortenedCount));

                        // Step 4: Re-render the lines with green color tracking
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
                System.out.println(" -> Successfully saved: " + outputFile.getAbsolutePath());
            } catch (IOException e) {
                System.err.println("Error processing " + inputFile.getName() + ": " + e.getMessage());
            }
        }
    }

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
            // Sort line points dynamically from bottom to top to make proximity checks reliable
            if (currentPoint.getY() <= y) {
                extractedLines.add(new Line2D.Float((float) currentPoint.getX(), (float) currentPoint.getY(), x, y));
            } else {
                extractedLines.add(new Line2D.Float(x, y, (float) currentPoint.getX(), (float) currentPoint.getY()));
            }
            this.currentPoint = new Point2D.Float(x, y);
        }

        @Override
        public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
            // Unpack rectangle coordinates consistently
            addLineNormalized(p0.getX(), p0.getY(), p1.getX(), p1.getY());
            addLineNormalized(p1.getX(), p1.getY(), p2.getX(), p2.getY());
            addLineNormalized(p2.getX(), p2.getY(), p3.getX(), p3.getY());
            addLineNormalized(p3.getX(), p3.getY(), p0.getX(), p0.getY());
        }

        private void addLineNormalized(double x1, double y1, double x2, double y2) {
            if (y1 <= y2) {
                extractedLines.add(new Line2D.Double(x1, y1, x2, y2));
            } else {
                extractedLines.add(new Line2D.Double(x2, y2, x1, y1));
            }
        }

        @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
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
