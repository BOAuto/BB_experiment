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
                rewritePage(page);
            }

            doc.save(output);
        }

        System.out.println("Saved: " + output);
    }

    private static void rewritePage(PDPage page) throws IOException {

        COSBase base = page.getCOSObject().getItem(COSName.CONTENTS);

        if (!(base instanceof COSStream cosStream)) return;

        // ✔ CORRECT way in PDFBox 3.x
        PDFStreamParser parser = new PDFStreamParser(cosStream);
        parser.parse();

        List<Object> tokens = parser.getTokens();
        List<Object> newTokens = new ArrayList<>();

        for (Object token : tokens) {

            if (token instanceof Operator op) {

                String name = op.getName();

                switch (name) {

                    case "rg":
                    case "RG":
                        newTokens.add(COSInteger.ZERO);
                        newTokens.add(COSInteger.ZERO);
                        newTokens.add(COSInteger.ZERO);
                        newTokens.add(op);
                        continue;

                    case "g":
                    case "G":
                        newTokens.add(COSInteger.ZERO);
                        newTokens.add(op);
                        continue;
                }
            }

            newTokens.add(token);
        }

        // ✔ SAFE replacement (PDFBox 3.x supported)
        PDStream newStream = new PDStream(page.getCOSObject().getCOSDocument());
        try (OutputStream out = newStream.createOutputStream()) {
            ContentStreamWriter writer = new ContentStreamWriter(out);
            writer.writeTokens(newTokens);
        }

        page.setContents(newStream);
    }
}
