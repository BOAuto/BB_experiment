import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
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

        if (!(base instanceof COSStream cosStream)) return;

        // ⚠️ ONLY SAFE CONSTRUCTOR IN YOUR VERSION
        PDFStreamParser parser = new PDFStreamParser(page);
        parser.parse();

        List<Object> tokens = parser.getTokens();
        List<Object> newTokens = new ArrayList<>();

        for (Object token : tokens) {

            if (token instanceof Operator op) {

                String opName = op.getName();

                switch (opName) {

                    // RGB stroke
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

            newTokens.add(token);
        }

        // ✔ SAFE STREAM REPLACEMENT
        PDStream newStream = new PDStream(doc);

        try (OutputStream out = newStream.createOutputStream()) {
            ContentStreamWriter writer = new ContentStreamWriter(out);
            writer.writeTokens(newTokens);
        }

        page.setContents(newStream);
    }
}
