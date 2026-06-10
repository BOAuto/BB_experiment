import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;

import java.io.File;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class VectorRecolor {

    public static void main(String[] args) throws Exception {

        String input = "pdfs/tcs_po3_rewritten.pdf";
        String output = "pdfs/output.pdf";

        try (PDDocument doc = Loader.loadPDF(new File(input))) {

            for (PDPage page : doc.getPages()) {
                rewrite(page, doc);
            }

            doc.save(output);
        }

        System.out.println("Saved: " + output);
    }

    private static void rewrite(PDPage page, PDDocument doc) throws Exception {

        // ✔ THIS is the ONLY constructor your compiler supports
        PDFStreamParser parser = new PDFStreamParser(page);
        parser.parse();

        List<Object> tokens = parser.getTokens();
        List<Object> newTokens = new ArrayList<>();

        for (Object t : tokens) {

            if (t instanceof Operator op) {

                String name = op.getName();

                switch (name) {

                    // stroke RGB
                    case "RG":
                    case "rg":
                        newTokens.add(0f);
                        newTokens.add(0f);
                        newTokens.add(0f);
                        newTokens.add(op);
                        continue;

                    // grayscale
                    case "G":
                    case "g":
                        newTokens.add(0f);
                        newTokens.add(op);
                        continue;
                }
            }

            newTokens.add(t);
        }

        // ✔ overwrite page safely
        PDPageContentStream cs = new PDPageContentStream(
                doc,
                page,
                PDPageContentStream.AppendMode.OVERWRITE,
                false
        );

        ContentStreamWriter writer = new ContentStreamWriter(cs.getOutputStream());
        writer.writeTokens(newTokens);

        cs.close();
    }
}
