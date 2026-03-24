---
name: pdf
description: Process PDF files - extract text, create PDFs, merge documents. Use when user asks to read PDF, create PDF, or work with PDF files.
---

# PDF Processing Skill

You now have expertise in PDF manipulation. Follow these workflows:

## Reading PDFs

**Option 1: Quick text extraction (preferred)**
```bash
# Using pdftotext (poppler-utils)
pdftotext input.pdf -  # Output to stdout
pdftotext input.pdf output.txt  # Output to file
```

**Option 2: Page-by-page with metadata (Apache PDFBox)**
```java
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import java.io.File;

PDDocument doc = PDDocument.load(new File("input.pdf"));
System.out.println("Pages: " + doc.getNumberOfPages());
System.out.println("Metadata: " + doc.getDocumentInformation().getTitle());

PDFTextStripper stripper = new PDFTextStripper();
for (int i = 1; i <= doc.getNumberOfPages(); i++) {
    stripper.setStartPage(i);
    stripper.setEndPage(i);
    String text = stripper.getText(doc);
    System.out.println("--- Page " + i + " ---");
    System.out.println(text);
}
doc.close();
```

## Creating PDFs

**Option 1: From Markdown (recommended)**
```bash
# Using pandoc
pandoc input.md -o output.pdf

# With custom styling
pandoc input.md -o output.pdf --pdf-engine=xelatex -V geometry:margin=1in
```

**Option 2: Programmatically (Apache PDFBox)**
```java
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

PDDocument doc = new PDDocument();
PDPage page = new PDPage();
doc.addPage(page);

try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
    content.beginText();
    content.setFont(PDType1Font.HELVETICA, 12);
    content.newLineAtOffset(100, 750);
    content.showText("Hello, PDF!");
    content.endText();
}
doc.save("output.pdf");
doc.close();
```

**Option 3: From HTML (OpenHTMLToPDF)**
```java
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

String html = Files.readString(Path.of("input.html"));
try (OutputStream os = new FileOutputStream("output.pdf")) {
    PdfRendererBuilder builder = new PdfRendererBuilder();
    builder.withHtmlContent(html, new File("input.html").toURI().toString());
    builder.toStream(os);
    builder.run();
}
```

## Merging PDFs

```java
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import java.io.File;

PDFMergerUtility merger = new PDFMergerUtility();
merger.addSource(new File("file1.pdf"));
merger.addSource(new File("file2.pdf"));
merger.addSource(new File("file3.pdf"));
merger.setDestinationFileName("merged.pdf");
merger.mergeDocuments(null);
```

## Splitting PDFs

```java
import org.apache.pdfbox.multipdf.Splitter;
import org.apache.pdfbox.pdmodel.PDDocument;
import java.io.File;
import java.util.List;

PDDocument doc = PDDocument.load(new File("input.pdf"));
Splitter splitter = new Splitter();
splitter.setSplitAtPage(1); // 每页拆分为一个文件
List<PDDocument> pages = splitter.split(doc);

for (int i = 0; i < pages.size(); i++) {
    pages.get(i).save("page_" + (i + 1) + ".pdf");
    pages.get(i).close();
}
doc.close();
```

## Key Libraries

| Task | Library | Maven Dependency |
|------|---------|-----------------|
| Read/Write/Merge/Split | Apache PDFBox | `org.apache.pdfbox:pdfbox:3.0.x` |
| Create from HTML | OpenHTMLToPDF | `com.openhtmltopdf:openhtmltopdf-pdfbox:1.0.x` |
| Advanced layout | iText | `com.itextpdf:itext7-core:8.0.x` |
| Text extraction | pdftotext | `brew install poppler` / `apt install poppler-utils` |

## Best Practices

1. **Always check if tools are installed** before using them
2. **Handle encoding issues** - PDFs may contain various character encodings
3. **Large PDFs**: Process page by page to avoid memory issues; use try-with-resources 确保资源释放
4. **OCR for scanned PDFs**: Use Tesseract4J (`net.sourceforge.tess4j:tess4j`) if text extraction returns empty
