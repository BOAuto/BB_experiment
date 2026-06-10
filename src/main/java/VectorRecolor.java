import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.contentstream.PDFStreamEngine;
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
                rewritePage(page, doc);
            }

            doc.save(output);
        }

        System.out.println("Saved: " + output);
    }

    private static void rewritePage(PDPage page, PDDocument doc) throws IOException {

        COSBase base = page.getCOSObject().getItem(COSName.CONTENTS);

        if (!(base instanceof COSStream)) return;

        COSStream cosStream = (COSStream) base;

        PDFStreamParser parser = new PDFStreamParser(cosStream);
        parser.parse();

        List<Object> tokens = parser.getTokens();
        List<Object> newTokens = new ArrayList<>();

        for (Object token : tokens) {

            if (token instanceof Operator op) {

                String name = op.getName();

                switch (name) {

                    // fill RGB
                    case "rg":
                    case "RG":
                        newTokens.add(COSInteger.ZERO);
                        newTokens.add(COSInteger.ZERO);
                        newTokens.add(COSInteger.ZERO);
                        newTokens.add(Operator.getOperator(name));
                        continue;

                    // grayscale
                    case "g":
                    case "G":
                        newTokens.add(COSInteger.ZERO);
                        newTokens.add(Operator.getOperator(name));
                        continue;
                }
            }

            newTokens.add(token);
        }

        // write back stream
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ContentStreamWriter writer = new ContentStreamWriter(out);
        writer.writeTokens(newTokens);

        cosStream.setUnfilteredStream(out.toByteArray());
    }
}
