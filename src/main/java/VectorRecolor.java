package main.java;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.Loader;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
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
            System.out.println("Processing Stream Mutations: " + inputFile.getName());
            System.out.println("------------------------------------------------");

            try (PDDocument document = Loader.loadPDF(inputFile)) {
                for (int i = 0; i < document.getNumberOfPages(); i++) {
                    PDPage page = document.getPage(i);
                    // Pass the document object down along with the page
                    mutatePageStream(document, page);
                }
                document.save(outputFile);
                System.out.println(" -> Successfully mutated and saved: " + outputFile.getAbsolutePath());
            } catch (IOException e) {
                System.err.println("Error mutating " + inputFile.getName() + ": " + e.getMessage());
            }
        }
    }

    private static void mutatePageStream(PDDocument document, PDPage page) throws IOException {
        PDFStreamParser parser = new PDFStreamParser(page);
        List<Object> tokens = parser.parse();
        List<Object> newTokens = new ArrayList<>();

        Float lastX = null;
        Float lastY = null;
        int mutationCount = 0;

        for (int j = 0; j < tokens.size(); j++) {
            Object token = tokens.get(j);

            if (token instanceof Operator) {
                Operator op = (Operator) token;
                String opName = op.getName();

                // 'm' defines a moveTo operation (sets the starting anchor point)
                if (opName.equals("m") && j >= 2) {
                    Object xToken = tokens.get(j - 2);
                    Object yToken = tokens.get(j - 1);
                    if (xToken instanceof COSNumber && yToken instanceof COSNumber) {
                        lastX = ((COSNumber) xToken).floatValue();
                        lastY = ((COSNumber) yToken).floatValue();
                    }
                }
                
                // 'l' defines a lineTo operation (draws a segment from last anchor point)
                else if (opName.equals("l") && j >= 2 && lastX != null && lastY != null) {
                    Object xToken = tokens.get(j - 2);
                    Object yToken = tokens.get(j - 1);

                    if (xToken instanceof COSNumber && yToken instanceof COSNumber) {
                        float targetX = ((COSNumber) xToken).floatValue();
                        float targetY = ((COSNumber) yToken).floatValue();

                        // TARGET RULE: Detect vertical grid lines (X matches, Y changes)
                        boolean isVerticalLine = Math.abs(targetX - lastX) < 0.5f;
                        boolean isSubstantial = Math.abs(targetY - lastY) > 4.0f;

                        if (isVerticalLine && isSubstantial) {
                            // Shorten the height down to a nominal 0.001 delta in-place
                            float shortenedY = lastY + (targetY > lastY ? 0.001f : -0.001f);
                            
                            // Mutate arguments safely in the array back-stack
                            newTokens.set(newTokens.size() - 2, new COSFloat(targetX));
                            newTokens.set(newTokens.size() - 1, new COSFloat(shortenedY));
                            
                            mutationCount++;
                            
                            lastX = targetX;
                            lastY = shortenedY;
                            newTokens.add(token);
                            continue;
                        }

                        lastX = targetX;
                        lastY = targetY;
                    }
                }
                
                // 're' defines a rectangle primitive directly [x, y, width, height]
                else if (opName.equals("re") && j >= 4) {
                    Object wToken = tokens.get(j - 2);
                    Object hToken = tokens.get(j - 1);
                    
                    if (wToken instanceof COSNumber && hToken instanceof COSNumber) {
                        float w = ((COSNumber) wToken).floatValue();
                        
                        // If this rectangle matches a narrow structural layout gap border
                        if (w > 3.0f && w < 22.0f) {
                            newTokens.set(newTokens.size() - 1, new COSFloat(0.001f));
                            mutationCount++;
                        }
                    }
                }
            }
            newTokens.add(token);
        }

        // Flush and overwrite using the corrected PDDocument reference
        if (mutationCount > 0) {
            PDStream updatedStream = new PDStream(document);
            try (OutputStream os = updatedStream.createOutputStream(COSName.FLATE_DECODE)) {
                ContentStreamWriter writer = new ContentStreamWriter(os);
                writer.writeTokens(newTokens);
            }
            page.setContents(updatedStream);
            System.out.println(String.format("  -> Successfully mutated %d vector layout primitives directly in stream.", mutationCount));
        }
    }
}
