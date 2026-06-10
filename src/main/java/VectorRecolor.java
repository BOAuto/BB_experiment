import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

public class VectorRecolor {

    public static void main(String[] args) throws Exception {

        String input = "pdfs/tcs_po3_rewritten.pdf";
        String output = "pdfs/output.pdf";

        try (PDDocument doc = Loader.loadPDF(new File(input))) {

            for (PDPage page : doc.getPages()) {
                rewrite(page);
            }

            doc.save(output);
        }

        System.out.println("Saved: " + output);
    }

    private static void rewrite(PDPage page) throws IOException {

        COSBase contents = page.getCOSObject().getItem(COSName.CONTENTS);

        if (!(contents instanceof COSStream cosStream)) {
            return;
        }

        // ✔ CORRECT constructor in PDFBox 3.x
        PDFStreamParser parser = new PDFStreamParser(page);
        parser.parse();

        List<Object> tokens = parser.getTokens();
        List<Object> newTokens = new ArrayList<>();

        for (Object token : tokens) {

            if (token instanceof Operator op) {

                String name = op.getName();

                switch (name) {

                    case "rg": // fill RGB
                    case "RG": // stroke RGB
                        newTokens.add(COSInteger.ZERO);
                        newTokens.add(COSInteger.ZERO);
                        newTokens.add(COSInteger.ZERO);
                        newTokens.add(op);
                        continue;

                    case "g": // grayscale
                    case "G":
                        newTokens.add(COSInteger.ZERO);
                        newTokens.add(op);
                        continue;
                }
            }

            newTokens.add(token);
        }

        // ✔ write new stream safely (PDFBox 3.x way)
        COSStream newStream = page.getCOSObject().getCOSDocument().createCOSStream();

        try (OutputStream out = newStream.createOutputStream()) {
            ContentStreamWriter writer = new ContentStreamWriter(out);
            writer.writeTokens(newTokens);
        }

        page.getCOSObject().setItem(COSName.CONTENTS, newStream);
    }
}
