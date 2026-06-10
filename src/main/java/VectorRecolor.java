public static void main(String[] args) throws Exception {

    if (args.length != 2) {
        System.out.println(
            "Usage: java VectorRecolor input.pdf output.pdf");
        System.exit(1);
    }

    String input = args[0];
    String output = args[1];

    try (PDDocument doc = Loader.loadPDF(new File(input))) {
        for (PDPage page : doc.getPages()) {
            processPage(page);
        }
        doc.save(output);
    }

    System.out.println("Saved: " + output);
}
