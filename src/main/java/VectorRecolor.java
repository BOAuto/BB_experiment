import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDStream;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

public class VectorRecolor {

    public static void main(String[] args) throws Exception {

        String input = "pdfs/tcs_po3_rewritten.pdf";
        String output = "pdfs/output.pdf";

        try (PDDocument doc = Loader.loadPDF(new File(input))) {

            for (PDPage page : doc.getPages()) {
                process(page, doc);
            }

            doc.save(output);
        }

        System.out.println("Saved: " + output);
    }

    private static void process(PDPage page, PDDocument doc) throws IOException {

        COSBase base = page.getCOSObject().getItem(COSName.CONTENTS);

        if (!(base instanceof COSStream cosStream)) {
            return;
        }

        // ✔ ONLY VALID CONSTRUCTOR IN YOUR BUILD
        PDFStreamParser parser = new PDFStreamParser(cosStream);
        parser.parse();

        List<Object> tokens = parser.getTokens();
        List<Object> outputTokens = new ArrayList<>();

        for (Object token : tokens) {

            if (token instanceof Operator op) {

                String name = op.getName();

                switch (name) {

                    // stroke RGB → black
                    case "RG":
                    case "rg":
                        outputTokens.add(COSInteger.ZERO);
                        outputTokens.add(COSInteger.ZERO);
                        outputTokens.add(COSInteger.ZERO);
                        outputTokens.add(op);
                        continue;

                    // grayscale
                    case "G":
                    case "g":
                        outputTokens.add(COSInteger.ZERO);
                        outputTokens.add(op);
                        continue;
                }
            }

            outputTokens.add(token);
        }

        // ✔ SAFE REPLACEMENT
        PDStream newStream = new PDStream(doc);

        try (OutputStream out = newStream.createOutputStream()) {
            ContentStreamWriter writer = new ContentStreamWriter(out);
            writer.writeTokens(outputTokens);
        }

        page.setContents(newStream);
    }
}
